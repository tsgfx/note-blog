#!/bin/sh
# 云端可用性探活，由 ~/Library/LaunchAgents/com.notesblog.health-check.plist 每 5 分钟调一次。
#
#   sh scripts/check-cloud-health.sh          # 手动跑一次
#   HEALTH_URL=http://127.0.0.1:8080/api/health sh scripts/check-cloud-health.sh
#
# 判定口径：HTTP 能连通 **且** body 里 status 是 up 才算健康。`/api/health` 的
# HTTP 码永远是 200（见 deploy/README.md），真正状态在 body，所以必须解析 JSON。
#
# 2026-11-20 起站点走 https://tsgfx.top（备案通过后启用域名 + Let's Encrypt）。
# 旧地址 http://<公网IP>/api/health 现在会 308 到 https://<IP>，然后因为证书
# 只签给域名而失败——别再把 IP 写回来。
#
# 局限：Mac 睡眠/关机时不探。要 24/7 就把同一个 URL 填进 UptimeRobot /
# Cloudflare Healthcheck / 阿里云云监控，接口这边不需要任何改动。
set -u

URL="${HEALTH_URL:-https://tsgfx.top/api/health}"
STATE="${TMPDIR:-/tmp}/notesblog-health.consecutive-failures"
THRESHOLD=2
REPEAT_EVERY=12
TIMEOUT=15

now() { date '+%Y-%m-%d %H:%M:%S'; }

# --noproxy '*'：探活要测的是"直连能不能通"，挂在本机代理后面测的是代理
BODY=$(curl -sS --noproxy '*' --max-time "$TIMEOUT" -w '\n%{http_code}' "$URL" 2>&1)
CURL_EXIT=$?
CODE=$(printf '%s' "$BODY" | tail -n 1)
PAYLOAD=$(printf '%s' "$BODY" | sed '$d')

if [ "$CURL_EXIT" -eq 0 ] && [ "$CODE" = "200" ] && printf '%s' "$PAYLOAD" | grep -q '"status":"up"'; then
  if [ -f "$STATE" ]; then
    rm -f "$STATE"
    echo "$(now) RECOVERED $URL"
  fi
  exit 0
fi

COUNT=$(cat "$STATE" 2>/dev/null || echo 0)
case "$COUNT" in ''|*[!0-9]*) COUNT=0 ;; esac
COUNT=$((COUNT + 1))
printf '%s' "$COUNT" > "$STATE"

# degraded 也要报出来：服务活着但 db/embedding 挂了，检索和问答是废的
STATUS=$(printf '%s' "$PAYLOAD" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p' | head -1)
echo "$(now) FAIL(${COUNT}) http=${CODE:-none} curl_exit=${CURL_EXIT} body_status=${STATUS:-none} url=$URL"

if [ "$COUNT" -ge "$THRESHOLD" ] &&
  { [ "$COUNT" -eq "$THRESHOLD" ] || [ $((COUNT % REPEAT_EVERY)) -eq 0 ]; }; then
  osascript -e "display notification \"连续 ${COUNT} 次探活失败（${STATUS:-无响应}）：${URL}\" with title \"一隅 · 站点不可用\" sound name \"Basso\"" >/dev/null 2>&1 || true
fi

exit 1
