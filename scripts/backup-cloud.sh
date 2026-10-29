#!/bin/sh
# 云端（阿里云 ECS）每日备份入口，由 cron 调用。
#
#   sh /opt/note-blog/scripts/backup-cloud.sh
#
# 与 scheduled-backup.sh 的分工：那个给 macOS launchd 用，这个给 ECS cron 用。
# 两者行为一致——成功清掉错误标记，失败留下 .last-backup-error，
# 后端（./backups:/data/backups:ro 挂载）读到它就在设置页亮黄条。
# 差别只在：这里固定用 docker-compose.prod.yml，PATH 按 Debian 走。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${BACKUP_DIR:-$ROOT/backups}"
MARKER="$DEST/.last-backup-error"
LOG="${BACKUP_LOG:-/var/log/notesblog-backup.log}"

# cron 的 PATH 极简，docker 在 /usr/bin；顺手带上 snap/flatpak 常见位置
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export PATH

export COMPOSE_FILE="$ROOT/docker-compose.prod.yml"
export BACKUP_DIR="$DEST"
export BACKUP_KEEP="${BACKUP_KEEP:-14}"
export BACKUP_FROM=docker

mkdir -p "$DEST"

say() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

say "云端备份开始（保留 $BACKUP_KEEP 份）"

# 数据库容器必须真的在跑：不在跑时 backup.sh 会退回本机 mysqldump，
# 而服务器上没有 mysql 客户端 —— 与其备出一个空文件，不如明确失败。
if ! docker compose -f "$COMPOSE_FILE" ps mysql --format '{{.Status}}' 2>/dev/null | grep -q 'Up'; then
  MESSAGE="备份失败于 $(date '+%Y-%m-%d %H:%M:%S')：mysql 容器未运行（先看 docker compose ps）"
  printf '%s\n' "$MESSAGE" >"$MARKER"
  say "$MESSAGE"
  exit 1
fi

if sh "$ROOT/scripts/backup.sh" 2>&1; then
  rm -f "$MARKER"
  say "备份成功：$(ls -1t "$DEST"/notes_blog_*.sql.gz 2>/dev/null | head -1)"
else
  MESSAGE="备份失败于 $(date '+%Y-%m-%d %H:%M:%S')，详情见服务器 $LOG"
  printf '%s\n' "$MESSAGE" >"$MARKER"
  say "$MESSAGE" >&2
  exit 1
fi

# 异地副本：加密后直传 COS。未配置时脚本自己跳过（exit 0）。
# 上传失败要可见——本地备份成功但没出机房，等同于没有异地副本。
if ! sh "$ROOT/scripts/backup-upload-cos.sh"; then
  MESSAGE="本机备份成功，但 COS 异地副本上传失败（$(date '+%Y-%m-%d %H:%M:%S')），见 $LOG"
  printf '%s\n' "$MESSAGE" >"$MARKER"
  say "$MESSAGE" >&2
  exit 1
fi

exit 0
