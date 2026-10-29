#!/bin/sh
# 改完代码后跑一次，重建对应服务的镜像并验证它真的生效了。
#
#   sh scripts/deploy.sh              # 按 git 改动自动判断要重建谁
#   sh scripts/deploy.sh frontend     # 只重建前端
#   sh scripts/deploy.sh backend      # 只重建后端
#   sh scripts/deploy.sh all          # 两个都重建
#
# 为什么要这个脚本：只跑 `npm run build` 或只重建 backend，都不会改变 8088 的页面——
# 静态文件是构建进镜像的。这个坑已经踩过两次，所以把它固化成一条命令。

set -eu

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8088}"

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || die "找不到 docker"

if ! docker info >/dev/null 2>&1; then
  die "Docker 没在运行。先执行 colima start（或打开 Docker Desktop）"
fi

# ── 决定要重建哪些服务 ────────────────────────────────
TARGET="${1:-auto}"
DO_FRONTEND=0
DO_BACKEND=0

case "$TARGET" in
  frontend)
    DO_FRONTEND=1
    ;;
  backend)
    DO_BACKEND=1
    ;;
  all)
    DO_FRONTEND=1
    DO_BACKEND=1
    ;;
  auto)
    CHANGED=$(git status --porcelain 2>/dev/null | awk '{print $2}' || true)
    [ -n "$CHANGED" ] || die "没有检测到未提交的改动。要强制重建就显式传 frontend / backend / all"

    # case 的通配能匹配跨行的字符串，所以整串一起判断即可
    case "$CHANGED" in *frontend/*) DO_FRONTEND=1 ;; esac
    case "$CHANGED" in *backend/*) DO_BACKEND=1 ;; esac

    [ "$DO_FRONTEND" = "1" ] || [ "$DO_BACKEND" = "1" ] ||
      die "改动的文件不在 frontend/ 或 backend/ 下，没有需要重建的服务"
    ;;
  *)
    die "参数只能是 frontend / backend / all，收到的是：$TARGET"
    ;;
esac

SERVICES=""
[ "$DO_FRONTEND" = "1" ] && SERVICES="frontend"
if [ "$DO_BACKEND" = "1" ]; then
  SERVICES="${SERVICES:+$SERVICES }backend"
fi

# ── 重建 ──────────────────────────────────────────────
info "重建：$SERVICES"
# shellcheck disable=SC2086
docker compose up -d --build $SERVICES

# ── 等待就绪 ──────────────────────────────────────────
# 注意：容器状态变成 running 时应用可能还没起完，这时 nginx 会回 502。
# 所以不能只看 docker ps，必须真敲健康检查，最多等 60 秒。
info "等待后端就绪…"
i=0
READY=0
while [ "$i" -lt 30 ]; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$BASE_URL/api/health" || true)
  if [ "$CODE" = "200" ]; then
    READY=1
    break
  fi
  i=$((i + 1))
  sleep 2
done

if [ "$READY" != "1" ]; then
  printf '\n'
  docker compose logs --tail 20 backend 2>/dev/null || true
  die "等了 60 秒 /api/health 仍不是 200（最后返回 ${CODE:-无响应}），日志见上"
fi

# ── 验证 ──────────────────────────────────────────────
info "验证接口"

# /api/health 的 HTTP 码永远是 200，真正的状态在 body 的 status 字段里：
# up = 依赖全通，degraded = 有依赖不可用。只看状态码会把"数据库挂了"判成成功。
HEALTH_BODY=$(curl -s -m 5 "$BASE_URL/api/health" || true)
HEALTH_STATUS=$(printf '%s' "$HEALTH_BODY" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p')

case "$HEALTH_STATUS" in
  up)
    ok "健康检查：依赖正常（db / ollama 均可用）"
    ;;
  degraded)
    # 把不可用的依赖列出来，省得再去手动 curl 一遍
    BAD=$(printf '%s' "$HEALTH_BODY" | sed -n 's/.*"components":{\([^}]*\)}.*/\1/p' |
      tr ',' '\n' | grep ':false' | cut -d'"' -f2 | tr '\n' ' ')
    warn "健康检查：degraded，不可用依赖 → ${BAD:-未知}"
    warn "接口本身能跑，但这些依赖相关功能会失败；查对应容器而不是重启后端"
    ;;
  *)
    warn "健康检查响应异常：${HEALTH_BODY:-无响应}"
    ;;
esac

if [ "$DO_FRONTEND" = "1" ]; then
  # 只取文件名比较：8088 返回的是 assets/index-xxx.js，本机 dist 里只有 index-xxx.js
  SERVED=$(curl -s -m 10 "$BASE_URL/" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1 | sed 's|.*/||' || true)
  LOCAL=$(ls frontend/dist/assets/index-*.js 2>/dev/null | sed 's|.*/||' | head -1 || true)

  if [ -n "$SERVED" ]; then
    ok "8088 正在提供 ${SERVED}"
  else
    warn "没能从 8088 首页解析出入口 JS，检查一下页面是否正常"
  fi

  # 本机 dist 可能落后于镜像（镜像在容器内构建），不一致只做提示，不算失败
  # 变量必须用 ${} 包住：紧跟中文标点时，sh 会把多字节字符的首字节当成变量名的一部分
  if [ -n "$LOCAL" ] && [ -n "$SERVED" ] && [ "$LOCAL" != "$SERVED" ]; then
    warn "本机 dist 是 ${LOCAL}，与 8088 不一致 —— 只是提示，以容器内构建为准"
  fi
fi

if [ "$DO_BACKEND" = "1" ]; then
  STATS=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE_URL/api/blog/stats" || true)
  if [ "$STATS" = "200" ]; then
    ok "GET /api/blog/stats 200"
  else
    warn "GET /api/blog/stats 返回 ${STATS}"
  fi
fi

printf '\n'
ok "完成。普通刷新浏览器即可，不需要强制刷新。"
