#!/bin/sh
# 一条命令上线：校验 .env → 构建启动 → 等健康 → 自检
#
#   sh deploy/up.sh
#   sh deploy/up.sh --no-build      # 只重启，不重新构建镜像
#
# 首次部署约 10~20 分钟（拉镜像 + Maven/npm 构建 + 证书签发）。
# 之后改前端代码要用 --no-build 之外的方式重建：镜像是构建产物，
# 静态文件在里面，只 restart 不会更新页面（老坑）。

set -eu

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

COMPOSE="docker compose -f docker-compose.prod.yml"
BUILD_FLAG="--build"
[ "${1:-}" = "--no-build" ] && BUILD_FLAG=""

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

# ── 前置检查 ──────────────────────────────────────────
command -v docker >/dev/null 2>&1 || die "没装 docker"
docker info >/dev/null 2>&1 || die "docker 没在运行（systemctl start docker）"

[ -f .env ] || die "缺少 .env：cp deploy/.env.prod.example .env 然后填空"

# shellcheck disable=SC1091
. ./.env

MISSING=""
for KEY in DOMAIN ACME_EMAIL SITE_BASE_URL CORS_ALLOWED_ORIGINS DB_PASSWORD MYSQL_ROOT_PASSWORD JWT_SECRET ADMIN_PASSWORD; do
  eval "VALUE=\${$KEY:-}"
  [ -n "$VALUE" ] || MISSING="$MISSING $KEY"
done
[ -z "$MISSING" ] || die "这些变量还没填：$MISSING"

# 最容易忘的两处：地址写成 localhost、或者白名单没跟着域名改
case "$SITE_BASE_URL" in
  http://*) warn "SITE_BASE_URL 不是 https：$SITE_BASE_URL —— RSS 里会输出明文链接" ;;
esac
case "$CORS_ALLOWED_ORIGINS" in
  *localhost* | *127.0.0.1*)
    warn "CORS_ALLOWED_ORIGINS 里还留着本机地址：$CORS_ALLOWED_ORIGINS"
    warn "浏览器即使同源发 POST 也带 Origin 头，白名单不准就是「页面能开、接口全 403」"
    ;;
esac
case "$CORS_ALLOWED_ORIGINS" in
  *"$DOMAIN"*) ;;
  *) warn "CORS_ALLOWED_ORIGINS 里没有 ${DOMAIN}，确认一下" ;;
esac

# ── 启动 ──────────────────────────────────────────────
info "启动容器（首次构建会比较久，别中断）"
# shellcheck disable=SC2086
$COMPOSE up -d $BUILD_FLAG

# ── 等就绪 ────────────────────────────────────────────
# 容器 running ≠ 应用起来了：nginx 这时还回 502，所以必须真敲接口
info "等待后端就绪（最多 180 秒）"
i=0
READY=0
while [ "$i" -lt 60 ]; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "https://$DOMAIN/api/health" || true)
  if [ "$CODE" = "200" ]; then
    READY=1
    break
  fi
  i=$((i + 1))
  sleep 3
done

if [ "$READY" != "1" ]; then
  printf '\n'
  $COMPOSE logs --tail 30 caddy 2>/dev/null || true
  $COMPOSE logs --tail 30 backend 2>/dev/null || true
  die "https://$DOMAIN/api/health 还没通（最后返回 ${CODE:-无响应}）。
常见原因：
  1. 域名还没解析到本机公网 IP / 控制台防火墙没开 80、443 → 证书签不下来
  2. CORS 或 SITE_BASE_URL 没配 → 接口 403（但 health 是放行的，通常不是这个）
  3. 后端启动失败 → 看上面 backend 日志"
fi

# ── 健康状态 ──────────────────────────────────────────
# /api/health 的 HTTP 码永远是 200，状态在 body.status 里
HEALTH_BODY=$(curl -s -m 5 "https://$DOMAIN/api/health" || true)
HEALTH_STATUS=$(printf '%s' "$HEALTH_BODY" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p')
case "$HEALTH_STATUS" in
  up) ok "健康检查：db / ollama 都可用" ;;
  degraded)
    BAD=$(printf '%s' "$HEALTH_BODY" | sed -n 's/.*"components":{\([^}]*\)}.*/\1/p' |
      tr ',' '\n' | grep ':false' | cut -d'"' -f2 | tr '\n' ' ')
    warn "degraded，不可用的依赖 → ${BAD:-未知}（接口能跑，相关功能会失败）"
    ;;
  *) warn "健康响应异常：${HEALTH_BODY:-无响应}" ;;
esac

# ── 上传目录权限 ──────────────────────────────────────
# 后端在容器里以非 root 的 appuser 跑；Linux 的 bind mount 不会帮你改属主，
# 所以首次部署常常是「页面正常、传图 500」。macOS 的 Docker Desktop 会忽略这点，
# 本地测不出来，只会在服务器上炸。
ASSET_UID=$($COMPOSE exec -T backend id -u 2>/dev/null | tr -d '\r' || true)
if [ -n "$ASSET_UID" ]; then
  if $COMPOSE exec -T backend test -w /data/assets 2>/dev/null; then
    ok "图片目录可写"
  else
    warn "容器写不了 ./storage/assets（宿主机属主和容器 appuser 的 uid=$ASSET_UID 不一致）"
    warn "修：sudo chown -R $ASSET_UID:$ASSET_UID storage/assets"
  fi
fi

# ── 首次部署：拉向量模型（openai 后端走外部 API，不需要 Ollama 模型）──
if [ "${EMBEDDING_BACKEND:-ollama}" != "openai" ]; then
  if ! $COMPOSE exec -T ollama ollama list 2>/dev/null | grep -q "${EMBEDDING_MODEL:-bge-m3}"; then
    warn "Ollama 里还没有 ${EMBEDDING_MODEL:-bge-m3}，正在拉取（约 1.2GB，慢的话等几分钟）"
    $COMPOSE exec -T ollama ollama pull "${EMBEDDING_MODEL:-bge-m3}" || warn "拉取失败，稍后手动补：docker compose -f docker-compose.prod.yml exec ollama ollama pull bge-m3"
  fi
fi

printf '\n'
ok "应用已上线：https://$DOMAIN"
printf '\n'
info "开始自检"
exec sh deploy/verify.sh
