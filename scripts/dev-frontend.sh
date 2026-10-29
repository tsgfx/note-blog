#!/bin/sh
# 启动前端开发服务器（Vite），代理到容器里的后端。
#
# 由 launchd 调用（见 ~/Library/LaunchAgents/com.notesblog.vite-dev.plist），
# 也可以手工执行：sh scripts/dev-frontend.sh
#
# launchd 的 PATH 很干净，找不到 nvm 装的 node，所以这里显式补上。

set -eu

cd "$(dirname "$0")/../frontend"

if ! command -v npm >/dev/null 2>&1; then
  PATH="/Users/ruichao/.workbuddy/binaries/node/versions/22.22.2/bin:$PATH"
  export PATH
fi

# 后端跑在容器里（映射到 8081）；想连本地 mvn 起的后端就覆盖这个变量
VITE_API_TARGET="${VITE_API_TARGET:-http://localhost:8081}"
export VITE_API_TARGET

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 启动 vite，代理目标 ${VITE_API_TARGET}"
exec npm run dev
