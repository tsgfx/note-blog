#!/bin/sh
# 每日维护入口：备份本机开发栈。
#
#   sh scripts/daily-maintenance.sh
#
# 只服务本地开发数据：本地 MySQL 容器没在跑时 scheduled-backup.sh 会直接跳过，
# 不会写失败标记。生产环境的备份在 ECS 上（scripts/backup-cloud.sh，每天 03:00），
# 备份后由服务器直接加密上传 COS，不再需要本机来拉异地副本。
#
# 输出全部交给 launchd 的 StandardOutPath，本脚本**不**再 tee 一遍——
# 两边都写同一个文件会让每行出现两次（这个坑踩过）。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${MAINT_LOG:-$HOME/Library/Logs/notesblog-backup.log}"

say() { printf '%s [daily] %s\n' "$(date '+%F %T')" "$*"; }

say "开始每日维护"

if sh "$ROOT/scripts/scheduled-backup.sh" >>"$LOG" 2>&1; then
  say "本地备份完成"
  exit 0
fi

say "本地备份失败（设置页会亮黄条）"
exit 1
