#!/bin/sh
# 上线自检：从公网视角检查「该通的通、该关的关」
#
#   sh deploy/verify.sh                 # 用 .env 里的 DOMAIN
#   HOST=1.2.3.4 sh deploy/verify.sh    # 端口探测改打 IP（域名还没解析时用）
#
# 每一项都对应一个踩过的坑，不是凑数的检查项。

set -u

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

[ -f .env ] || {
  printf '缺少 .env\n' >&2
  exit 1
}
# shellcheck disable=SC1091
. ./.env

DOMAIN="${DOMAIN:-}"
[ -n "$DOMAIN" ] || {
  printf '.env 里没有 DOMAIN\n' >&2
  exit 1
}
HOST="${HOST:-$DOMAIN}"

PASS=0
FAIL=0

pass() {
  PASS=$((PASS + 1))
  printf '\033[32m✓\033[0m %s\n' "$1"
}
fail() {
  FAIL=$((FAIL + 1))
  printf '\033[31m✗\033[0m %s\n' "$1"
}
# 「应该关掉」的检查：连得上才是问题
expect_closed() {
  # --noproxy：机器上若配了 http_proxy，curl 会经代理探测，拿到 502 误判成「开着」
  if curl -s -m 3 --noproxy '*' -o /dev/null "http://$HOST:$1/" 2>/dev/null; then
    fail "$HOST:$1 对外开放了（应该只有 22/80/443）"
  else
    pass "$HOST:$1 已关闭"
  fi
}

printf '自检目标：https://%s   （端口探测：%s）\n\n' "$DOMAIN" "$HOST"

# ── 1. 域名解析 ──────────────────────────────────────
if IP=$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1; exit}' || true); [ -n "$IP" ]; then
  pass "$DOMAIN 解析到 $IP"
else
  fail "$DOMAIN 解析不到（DNS 还没生效或 A 记录没配）"
fi

# ── 2. HTTP 自动跳 HTTPS ─────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 8 "http://$DOMAIN/" || true)
case "$CODE" in
  301 | 302 | 308) pass "http 跳转 https（${CODE}）" ;;
  *) fail "http 没有跳转 https，返回 $CODE" ;;
esac

# ── 3. 首页 ──────────────────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "https://$DOMAIN/" || true)
[ "$CODE" = "200" ] && pass "首页 200" || fail "首页返回 $CODE"

# ── 4. HSTS ──────────────────────────────────────────
if curl -sI -m 10 "https://$DOMAIN/" | grep -qi 'strict-transport-security'; then
  pass "HSTS 已下发"
else
  fail "缺 Strict-Transport-Security 头（检查 deploy/Caddyfile）"
fi

# ── 5. 健康接口 ──────────────────────────────────────
BODY=$(curl -s -m 10 "https://$DOMAIN/api/health" || true)
STATUS=$(printf '%s' "$BODY" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p')
case "$STATUS" in
  up) pass "健康检查 up（db / ollama 均可用）" ;;
  degraded) fail "健康检查 degraded：$(printf '%s' "$BODY" | head -c 200)" ;;
  *) fail "健康检查响应异常：$(printf '%s' "$BODY" | head -c 200)" ;;
esac

# ── 6. 证书剩余天数 ──────────────────────────────────
END=$(echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null |
  openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2 || true)
if [ -n "$END" ]; then
  if END_TS=$(date -d "$END" +%s 2>/dev/null); then
    :
  else
    END_TS=$(date -j -f "%b %d %T %Y %Z" "$END" +%s 2>/dev/null || echo 0)
  fi
  DAYS=$(((END_TS - $(date +%s)) / 86400))
  if [ "$DAYS" -gt 14 ]; then
    pass "TLS 证书还剩 $DAYS 天（自动续期）"
  else
    fail "TLS 证书只剩 $DAYS 天，检查 caddy 日志是否续期失败"
  fi
else
  fail "拿不到 TLS 证书信息"
fi

# ── 7. 内部端口不该暴露 ──────────────────────────────
# Docker 发布的端口会绕过 ufw，所以必须从外部实测，不能只看 ufw status
expect_closed 8080
expect_closed 8081
expect_closed 8088
expect_closed 3306
expect_closed 11434

# ── 8. 跨域白名单真的生效 ────────────────────────────
# 页面能开、接口全 403 就是这里配错；反过来白名单太宽等于没配
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H 'Origin: https://evil.example' "https://$DOMAIN/api/blog/stats" || true)
case "$CODE" in
  403) pass "陌生 Origin 被拒（403）" ;;
  *) fail "陌生 Origin 返回 ${CODE}，应为 403（CORS_ALLOWED_ORIGINS 可能过宽）" ;;
esac

# 自己的域名要能过（同源请求也带 Origin 头）
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "Origin: https://$DOMAIN" "https://$DOMAIN/api/blog/stats" || true)
case "$CODE" in
  200) pass "自身域名 CORS 通过（200）" ;;
  *) fail "自身域名 CORS 返回 ${CODE}（页面会「能开但接口全挂」）" ;;
esac

# ── 9. 敏感路径被拦 ──────────────────────────────────
for PATH_ in "/.env" "/.git/config" "/actuator/health" "/wp-login.php"; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 8 "https://$DOMAIN$PATH_" || true)
  case "$CODE" in
    403 | 404) pass "$PATH_ 被拦（${CODE}）" ;;
    *) fail "$PATH_ 返回 ${CODE}，应被拦成 404" ;;
  esac
done

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m全部通过：%d 项\033[0m\n' "$PASS"
  exit 0
fi
printf '\033[31m%d 项失败 / %d 项通过\033[0m\n' "$FAIL" "$PASS"
exit 1
