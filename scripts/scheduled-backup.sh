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

# 本地栈可能有意停用（日常只在云端写笔记）：MySQL 容器没在跑就跳过，不算失败。
# 否则每天 03:00 都会写 .last-backup-error，设置页永远挂着一条黄条。
# 云端备份是 ECS 上的独立 cron，不受这里影响。
COMPOSE="${COMPOSE_FILE:-$ROOT/docker-compose.yml}"
if ! docker compose -f "$COMPOSE" ps mysql --format '{{.Status}}' 2>/dev/null | grep -q '^Up'; then
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] 本地 MySQL 未运行，跳过本地备份（云端备份独立运行）"
  rm -f "$MARKER"
  exit 0
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
