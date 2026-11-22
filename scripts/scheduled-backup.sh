#!/bin/sh
# 定时备份入口（给 launchd 调用）。
#
#   sh scripts/scheduled-backup.sh
#
# 与 backup.sh 的区别：它负责「让失败可见」。
# 备份最大的风险不是备不出来，而是三个月后才发现从没成功过——
# 所以成功时清掉错误标记，失败时留下一个标记文件，
# 后端读到它就会在设置页显示黄色警告条。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${BACKUP_DIR:-$ROOT/backups}"
MARKER="$DEST/.last-backup-error"

mkdir -p "$DEST"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 开始备份"

# launchd 不会加载 shell 配置，PATH 里没有 /usr/local/bin 这类目录，
# 而 docker 命令通常装在那里；显式补上，避免「手动能跑、定时跑不了」
PATH="/usr/local/bin:/opt/homebrew/bin:$PATH"
export PATH

# 本地栈可能有意停用（日常只在云端写笔记）：MySQL 既没在容器里、也没在本机监听，
# 才跳过，不算失败。否则每天 03:00 都会写 .last-backup-error，设置页永远挂着黄条。
# 云端备份是 ECS 上的独立 cron，不受这里影响。
#
# 以前只认 docker compose 的容器，于是「本机原生 MySQL」的部署被误判成「没在跑」，
# 静默跳过了一个多月（backups/ 里最后一份停在 2026-10-28）。所以三种状态分开判：
#   容器 Up        → backup.sh 走 docker 模式（凭据由容器自带）
#   本机 3306 在听  → backup.sh 走 local 模式（需要 .env 里的 DB_PASSWORD）
# 探测用 nc 而不是 lsof：lsof -sTCP:LISTEN 对别的用户（mysqld 常以 mysql 跑）
# 看不到套接字，会把「在跑」误判成「没在跑」——第一次改这里就踩过这个坑。
#   两边都没有      → 跳过
COMPOSE="${COMPOSE_FILE:-$ROOT/docker-compose.yml}"

if docker compose -f "$COMPOSE" ps mysql --format '{{.Status}}' 2>/dev/null | grep -q '^Up'; then
  LOCAL_MYSQL=0
elif nc -z -G 2 127.0.0.1 3306 >/dev/null 2>&1; then
  LOCAL_MYSQL=1
else
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] 本地 MySQL 未运行（无容器、3306 无监听），跳过本地备份（云端备份独立运行）"
  rm -f "$MARKER"
  exit 0
fi

# 本机模式要口令。launchd 不加载 shell 配置，这里显式从 .env 取，
# 取不到就报错并留标记——宁可让黄条亮，也不要再静默跳过。
if [ "$LOCAL_MYSQL" = "1" ]; then
  if [ -f "$ROOT/.env" ]; then
    DB_USER="$(grep -E '^DB_USER=' "$ROOT/.env" | tail -1 | cut -d= -f2-)"
    DB_NAME="$(grep -E '^DB_NAME=' "$ROOT/.env" | tail -1 | cut -d= -f2-)"
    DB_PASSWORD="$(grep -E '^DB_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)"
    [ -n "${DB_USER:-}" ] && export DB_USER
    [ -n "${DB_NAME:-}" ] && export DB_NAME
    [ -n "${DB_PASSWORD:-}" ] && export DB_PASSWORD
  fi
  if [ -z "${DB_PASSWORD:-}" ]; then
    MESSAGE="本机 MySQL 在跑，但 .env 里没有 DB_PASSWORD，无法备份于 $(date '+%Y-%m-%d %H:%M:%S')"
    printf '%s\n' "$MESSAGE" >"$MARKER"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $MESSAGE" >&2
    exit 1
  fi
fi

if sh "$ROOT/scripts/backup.sh"; then
  rm -f "$MARKER"
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] 备份成功"
  exit 0
fi

# 失败：写下标记，设置页会显示这条消息
MESSAGE="备份失败于 $(date '+%Y-%m-%d %H:%M:%S')，详见 ~/Library/Logs/notesblog-backup.log"
printf '%s\n' "$MESSAGE" >"$MARKER"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] $MESSAGE" >&2
exit 1
