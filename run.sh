#!/bin/sh
# 启动本地笔记博客（后端 8080 + 前端 5173）
# 用法：
#   ./run.sh            同时启动前后端
#   ./run.sh backend    只启动后端
#   ./run.sh frontend   只启动前端
set -eu

ROOT="$(cd "$(dirname "$0")" && pwd)"

# 从项目内的 .env 读取密钥等配置（不依赖任何其它目录）
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

start_backend() {
  echo "启动后端 http://127.0.0.1:8080"
  cd "$ROOT/backend"
  exec mvn -q spring-boot:run
}

start_frontend() {
  echo "启动前端 http://127.0.0.1:5173"
  cd "$ROOT/frontend"
  [ -d node_modules ] || npm install
  exec npm run dev -- --host "${HOST:-127.0.0.1}"
}

case "${1:-all}" in
  backend) start_backend ;;
  frontend) start_frontend ;;
  all)
    start_backend &
    BACKEND_PID=$!
    trap 'kill $BACKEND_PID 2>/dev/null || true' INT TERM
    start_frontend
    ;;
  *) echo "用法: $0 [all|backend|frontend]"; exit 2 ;;
esac
