#!/bin/sh
# 校园内网部署：构建启动 → 等健康 → 自检
#
#   sh deploy/intranet/up.sh
#   sh deploy/intranet/up.sh --no-build
#
# 和 deploy/up.sh 的区别：没有 Caddy、没有证书，健康检查走明文 HTTP，
# 地址来自 .env 里的 SITE_BASE_URL（内网 IP 或学校内网主机名）。

set -eu

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT_DIR"

COMPOSE="docker compose -f docker-compose.prod.yml -f deploy/intranet/override.yml"
BUILD_FLAG="--build"
[ "${1:-}" = "--no-build" ] && BUILD_FLAG=""

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || die "没装 docker"
docker info >/dev/null 2>&1 || die "docker 没在运行"
[ -f .env ] || die "缺少 .env：cp deploy/intranet/.env.example .env 然后填空"

# shellcheck disable=SC1091
. ./.env

MISSING=""
for KEY in SITE_BASE_URL CORS_ALLOWED_ORIGINS DB_PASSWORD MYSQL_ROOT_PASSWORD JWT_SECRET ADMIN_PASSWORD; do
  eval "VALUE=\${$KEY:-}"
  [ -n "$VALUE" ] || MISSING="$MISSING $KEY"
done
[ -z "$MISSING" ] || die "这些变量还没填：$MISSING"

# 最容易填错的：把本机的 localhost 当成访问地址，别人打开全是 403
case "$SITE_BASE_URL" in
  *localhost* | *127.0.0.1*)
    warn "SITE_BASE_URL 还是本机地址（${SITE_BASE_URL}），别人访问不了。"
    warn "改成服务器的内网 IP 或学校内网主机名，例如 http://10.20.30.40"
    ;;
esac
case "$CORS_ALLOWED_ORIGINS" in
  *localhost* | *127.0.0.1*) warn "CORS_ALLOWED_ORIGINS 里还有 localhost，内网用户会被 403" ;;
esac

info "启动（首次构建较久）"
# shellcheck disable=SC2086
$COMPOSE up -d $BUILD_FLAG mysql ollama backend frontend

info "等待后端就绪（最多 180 秒）"
i=0
READY=0
while [ "$i" -lt 60 ]; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$SITE_BASE_URL/api/health" || true)
  if [ "$CODE" = "200" ]; then
    READY=1
    break
  fi
  i=$((i + 1))
  sleep 3
done

if [ "$READY" != "1" ]; then
  printf '\n'
  $COMPOSE logs --tail 30 backend 2>/dev/null || true
  die "连不上 ${SITE_BASE_URL}/api/health（最后返回 ${CODE:-无响应}）。
常见原因：
  1. BIND_IP 绑错了网卡 → 换内网 IP，或先留空绑 0.0.0.0 再排错
  2. 端口被学校防火墙/宿主机别的进程占着 → ss -ltnp | grep 80
  3. 后端启动失败 → 看上面日志"
fi

# HTTP 码永远是 200，状态在 body 里
BODY=$(curl -s -m 5 "$SITE_BASE_URL/api/health" || true)
case "$BODY" in
  *'"status":"up"'*) ok "健康检查 up（db + ollama 均可用）" ;;
  *'"status":"degraded"'*) warn "健康检查 degraded：$BODY" ;;
  *) warn "健康检查响应异常：$BODY" ;;
esac

# 非 root 用户 + bind mount：Linux 上不改属主就传不了图
ASSET_UID=$($COMPOSE exec -T backend id -u 2>/dev/null | tr -d '\r' || true)
if [ -n "$ASSET_UID" ]; then
  if $COMPOSE exec -T backend test -w /data/assets 2>/dev/null; then
    ok "图片目录可写"
  else
    warn "容器写不了 ./storage/assets，修：sudo chown -R $ASSET_UID:$ASSET_UID storage/assets"
  fi
fi

if [ "${EMBEDDING_BACKEND:-ollama}" != "openai" ]; then
  if ! $COMPOSE exec -T ollama ollama list 2>/dev/null | grep -q "${EMBEDDING_MODEL:-bge-m3}"; then
    warn "Ollama 里还没有 ${EMBEDDING_MODEL:-bge-m3}，正在拉取（约 1.2GB）"
    $COMPOSE exec -T ollama ollama pull "${EMBEDDING_MODEL:-bge-m3}" ||
      warn "拉取失败，手动补：docker compose -f docker-compose.prod.yml -f deploy/intranet/override.yml exec ollama ollama pull bge-m3"
  fi
fi

printf '\n'
ok "应用已上线：$SITE_BASE_URL"
info "下一步：sudo sh deploy/intranet/firewall.sh 把端口只放给校园网段"
printf '\n'
info "开始自检"

OWN_ORIGIN=$(printf '%s' "$CORS_ALLOWED_ORIGINS" | cut -d, -f1)
BASE="$SITE_BASE_URL" OWN_ORIGIN="$OWN_ORIGIN" ENV_FILE=.env \
  ADMIN_PASSWORD="$ADMIN_PASSWORD" exec sh deploy/rehearsal/check.sh
