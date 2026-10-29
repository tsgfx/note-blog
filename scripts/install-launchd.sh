#!/bin/sh
# 安装 / 重装本机的 launchd 定时任务。
#
#   sh scripts/install-launchd.sh              # 安装（会先卸掉旧任务）
#   sh scripts/install-launchd.sh --uninstall  # 全部卸载
#   sh scripts/install-launchd.sh --status     # 只看当前状态
#
# 为什么必须自己执行：launchctl bootstrap 在非交互会话里会报
# 「Bootstrap failed: 5: Input/output error」，自动化环境里跑不了。
#
# 现在只剩一个任务：
#   com.notesblog.daily       每日 03:00 备份本机开发栈（栈没起时自动跳过）
#
# 生产环境已经与本机解耦：部署由服务器 git pull、探活交给外部服务、
# 异地备份由服务器直传 COS。本机任务只服务本地开发数据。
#
# 已废弃（会在安装时自动卸掉）：usage、health-check、backup、backup-pull、
# claude-sync、usage-collector、usage-sync-cloud。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENTS="$HOME/Library/LaunchAgents"
UID_NUM="$(id -u)"

ACTIVE="com.notesblog.daily"
RETIRED="com.notesblog.usage com.notesblog.health-check \
com.notesblog.backup com.notesblog.backup-pull com.notesblog.claude-sync \
com.notesblog.usage-collector com.notesblog.usage-sync-cloud"

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }

status() {
  printf '%-32s %s\n' 'LABEL' 'STATUS'
  for label in $ACTIVE $RETIRED; do
    if launchctl print "gui/$UID_NUM/$label" >/dev/null 2>&1; then
      printf '%-32s %s\n' "$label" 'loaded'
    else
      printf '%-32s %s\n' "$label" '-'
    fi
  done
}

if [ "${1:-}" = "--status" ]; then
  status
  exit 0
fi

# ── 卸载 ─────────────────────────────────────────────
bootout() {
  launchctl bootout "gui/$UID_NUM/$1" >/dev/null 2>&1 || true
}

if [ "${1:-}" = "--uninstall" ]; then
  info "卸载全部 notesblog 定时任务"
  for label in $ACTIVE $RETIRED; do
    bootout "$label"
    rm -f "$AGENTS/$label.plist"
  done
  ok "已全部卸载"
  exit 0
fi

# ── 安装 ─────────────────────────────────────────────
mkdir -p "$AGENTS"

info "先卸掉旧任务（含已废弃的）"
for label in $ACTIVE $RETIRED; do
  bootout "$label"
done
for label in $RETIRED; do
  rm -f "$AGENTS/$label.plist"
done

info "安装新任务"
for label in $ACTIVE; do
  src="$ROOT/scripts/$label.plist"
  [ -f "$src" ] || { warn "缺少 $src"; exit 1; }
  cp "$src" "$AGENTS/$label.plist"
  # RunAtLoad 都是 false，bootstrap 不会立刻跑一次，装完是干净的
  launchctl bootstrap "gui/$UID_NUM" "$AGENTS/$label.plist" || {
    warn "加载 $label 失败，检查 $src 的路径与权限"
    exit 1
  }
  ok "$label"
done

printf '\n'
status
printf '\n日志：\n  ~/Library/Logs/notesblog-backup.log\n'
