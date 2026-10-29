#!/bin/sh
# 备份数据库与图片目录，并只保留最近 N 份。
#
#   scripts/backup.sh                        # 自动判断数据在容器还是本机
#   DB_PASSWORD=... scripts/backup.sh        # 仅本机 mysqldump 模式需要密码
#
# 可选环境变量：
#   BACKUP_DIR    备份目录，默认 <项目>/backups
#   BACKUP_KEEP   保留份数，默认 14
#   DB_USER       数据库账号，默认 notes（容器模式忽略，用容器内的 MYSQL_USER）
#   DB_NAME       数据库名，默认 notes_blog
#   BACKUP_FROM   auto | docker | local，默认 auto
#   COMPOSE_FILE  用哪套编排判断容器，默认 docker-compose.yml；
#                 服务器上是 docker-compose.prod.yml，务必显式指定
#   DB_PASSWORD   数据库密码，**只有 local 模式需要**；容器模式用容器自带的凭据
#
# auto：容器里的 mysql 在跑就备份容器，否则退回本机 mysqldump。
# 这条区分很重要——数据迁到容器后，如果脚本还在连 127.0.0.1，
# 备出来的会是迁移前的旧库，看着成功其实全错。
set -eu

# 关于 note_chunks（重要）：
#   这张表有 VECTOR(1024) 列，mysqldump 导出的是二进制字符串，导回去 MySQL 9 直接拒收
#   （ERROR 6136: Value of type 'string' cannot be converted to 'vector'）——
#   也就是说**整库 dump 看着成功，其实无法恢复**。2026-10-27 恢复演练时才发现。
#   所以这里把它排除在数据之外，只用 --no-data 补一份空表结构；
#   恢复后分片由 POST /api/notes/reindex 重建（分片本来就是派生数据）。

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${BACKUP_DIR:-$ROOT/backups}"
KEEP="${BACKUP_KEEP:-14}"
DB_USER="${DB_USER:-notes}"
DB_NAME="${DB_NAME:-notes_blog}"
FROM="${BACKUP_FROM:-auto}"
# 生产环境是另一套编排文件：不指定就会误判「容器没在跑」，
# 然后退回本机 mysqldump —— 服务器上没有 mysql 客户端，直接失败
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/docker-compose.yml}"
STAMP="$(date +%Y%m%d_%H%M%S)"

mkdir -p "$DEST"

# 判断数据到底在哪一边
container_running() {
  docker compose -f "$COMPOSE_FILE" ps mysql --format '{{.Status}}' 2>/dev/null | grep -q 'Up'
}

dump_database() {
  if [ "$FROM" = "docker" ] || { [ "$FROM" = "auto" ] && container_running; }; then
    echo "来源：容器 mysql（docker compose exec）" >&2
    # 用容器里自带的 MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE，
    # 宿主机不需要知道密码——定时任务因此不必把密码写进 plist 或 .env。
    # --skip-masking-policies：MySQL 9 会尝试导出掩码策略，普通账号没有
    # SELECT column_masking_policy 权限，不跳过就会报错
    docker compose -f "$COMPOSE_FILE" exec -T -e DB_NAME="$DB_NAME" mysql \
      sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" \
        --single-transaction --set-gtid-purged=OFF --skip-masking-policies \
        --routines --no-tablespaces --ignore-table="$DB_NAME.note_chunks" --databases "$DB_NAME"'
    # 分片表只留结构：它的数据（VECTOR 列）导不回去，恢复后由 reindex 重建
    docker compose -f "$COMPOSE_FILE" exec -T -e DB_NAME="$DB_NAME" mysql \
      sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" \
        --single-transaction --set-gtid-purged=OFF --skip-masking-policies \
        --routines --no-tablespaces --no-data "$DB_NAME" note_chunks'
    return
  fi

  : "${DB_PASSWORD:?本机备份需要设置 DB_PASSWORD 环境变量}"
  echo "来源：本机 mysqldump" >&2
  # 兼容 brew 与官方安装包的两个常见位置
  MYSQLDUMP="${MYSQLDUMP_BIN:-}"
  if [ -z "$MYSQLDUMP" ]; then
    for candidate in "$(command -v mysqldump 2>/dev/null || true)" /usr/local/mysql/bin/mysqldump; do
      if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        MYSQLDUMP="$candidate"
        break
      fi
    done
  fi
  if [ -z "$MYSQLDUMP" ]; then
    echo "找不到 mysqldump，也没在跑容器。可用 MYSQLDUMP_BIN 指定路径。" >&2
    exit 1
  fi
  # 本机 mysqldump 版本不确定，先探一下有没有这个开关
  MASK_OPT=""
  if "$MYSQLDUMP" --help 2>/dev/null | grep -q skip-masking-policies; then
    MASK_OPT="--skip-masking-policies"
  fi
  MYSQL_PWD="$DB_PASSWORD" "$MYSQLDUMP" --protocol=TCP -h 127.0.0.1 -u "$DB_USER" \
    --single-transaction --set-gtid-purged=OFF $MASK_OPT --routines --no-tablespaces \
    --ignore-table="$DB_NAME.note_chunks" --databases "$DB_NAME"
  # 分片表只留结构（同上）
  MYSQL_PWD="$DB_PASSWORD" "$MYSQLDUMP" --protocol=TCP -h 127.0.0.1 -u "$DB_USER" \
    --single-transaction --set-gtid-purged=OFF $MASK_OPT --routines --no-tablespaces \
    --no-data "$DB_NAME" note_chunks
}

# 先转储到临时文件并检查退出码；管道会掩盖 mysqldump 的失败。
SQL="$DEST/.tmp_$STAMP.sql"
DUMP="$DEST/notes_blog_$STAMP.sql.gz"
if ! dump_database > "$SQL"; then
  rm -f "$SQL"
  echo "数据库备份失败，未生成备份文件" >&2
  exit 1
fi
gzip -c "$SQL" > "$DUMP"
rm -f "$SQL"
gzip -t "$DUMP"

# 图片目录可能不存在、为空、或只有空子目录（云端图片走 COS，storage/assets 是空壳）。
# tar 空目录只会备出一个几 KB 的空包，缺目录时配合 set -e 还会让整次备份失败——
# 所以按「有没有真实文件」判断，没有就跳过。
STORAGE_TAR="$DEST/storage_$STAMP.tar.gz"
if [ -n "$(find "$ROOT/storage" -type f -print -quit 2>/dev/null)" ]; then
  tar -C "$ROOT" -czf "$STORAGE_TAR" storage
else
  STORAGE_TAR=""
  echo "跳过图片备份：$ROOT/storage 下没有文件（云端图片在 COS）" >&2
fi

# 保留策略：每类只留最新 KEEP 份
ls -1t "$DEST"/notes_blog_*.sql.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs rm -f
ls -1t "$DEST"/storage_*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs rm -f

echo "数据库备份：${DUMP}（$(du -h "${DUMP}" | cut -f1)）"
if [ -n "$STORAGE_TAR" ]; then
  echo "图片备份：${STORAGE_TAR}（$(du -h "$STORAGE_TAR" | cut -f1)）"
fi
