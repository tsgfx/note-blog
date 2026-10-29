#!/bin/sh
# 预演自检：把「部署后一定会踩的坑」逐条验一遍
#
#   BASE=http://127.0.0.1:8090 ADMIN_PASSWORD=xxx sh deploy/rehearsal/check.sh
#   BASE=https://notes.example.com ADMIN_PASSWORD=xxx sh deploy/rehearsal/check.sh   # 真机也能用
#
# 和 deploy/verify.sh 的分工：
#   verify.sh  —— 公网视角（DNS / 证书 / 端口暴露 / HSTS），要有域名和 Caddy
#   check.sh   —— 应用视角（鉴权 / 检索 / 限流 / 静态资源），有容器就能跑
# 预演阶段用这个；上线后两个都跑。

set -u

BASE="${BASE:-http://127.0.0.1:8090}"
# 变量从哪个 env 文件兜底：预演读 rehearsal/.env，内网/真机传 ENV_FILE=.env
ENV_FILE="${ENV_FILE:-deploy/rehearsal/.env}"
if [ -z "${ADMIN_PASSWORD:-}" ] && [ -f "$ENV_FILE" ]; then
  ADMIN_PASSWORD=$(sed -n 's/^ADMIN_PASSWORD=//p' "$ENV_FILE")
fi
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"

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
code() { curl -s -o /dev/null -w '%{http_code}' -m "${2:-10}" "$1"; }

printf '预演自检：%s\n\n' "$BASE"

# ── 1. 依赖健康 ──────────────────────────────────────
# HTTP 码永远是 200，状态在 body.status
BODY=$(curl -s -m 10 "$BASE/api/health" || true)
case "$BODY" in
  *'"status":"up"'*) pass "health up（db + ollama 均可用）" ;;
  *'"status":"degraded"'*) fail "health degraded：$BODY" ;;
  *) fail "health 无响应或格式异常：$BODY" ;;
esac

# ── 2. 数据是否真在（schema.sql 只在空库时执行，恢复备份后也该有数）──
STATS=$(curl -s -m 10 "$BASE/api/blog/stats" || true)
NOTES=$(printf '%s' "$STATS" | sed -n 's/.*"notes":\([0-9]*\).*/\1/p')
if [ -n "$NOTES" ] && [ "$NOTES" -gt 0 ] 2>/dev/null; then
  pass "数据库有 $NOTES 篇笔记"
else
  fail "笔记数为 0 或读不到：$STATS"
fi

# ── 3. 登录（建表 / seed / 密码哈希 / JWT 全在这一条里）──
LOGIN=$(curl -s -m 10 -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" || true)
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  pass "登录成功，拿到 JWT"
else
  fail "登录失败：$(printf '%s' "$LOGIN" | head -c 160)"
fi

# ── 4. 鉴权链路：带 token 能读，不带要 401 ─────────────
if [ -n "$TOKEN" ]; then
  C_AUTH=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "Authorization: Bearer $TOKEN" "$BASE/api/notes")
  [ "$C_AUTH" = "200" ] && pass "带 token 读 /api/notes 200" || fail "带 token 读 /api/notes 返回 $C_AUTH"
fi
C_ANON=$(code "$BASE/api/notes")
[ "$C_ANON" = "401" ] && pass "匿名读 /api/notes 被拒（401）" || fail "匿名读 /api/notes 返回 ${C_ANON}，应为 401"

# ── 5. 语义检索：Ollama 模型 + 向量列 + 融合排序 ─────────
# 首次调用要加载模型，给足 90 秒
if [ -n "$TOKEN" ]; then
  HITS=$(curl -s -m 90 -X POST "$BASE/api/search" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"question":"docker 部署","limit":5}' | grep -o '"slug"' | wc -l | tr -d ' ')
  if [ "${HITS:-0}" -gt 0 ] 2>/dev/null; then
    pass "语义检索返回 $HITS 条（Ollama 向量链路通）"
  else
    fail "语义检索没结果 —— 检查 ollama 是否拉过 bge-m3、note_chunks 是否有 embedding"
  fi
fi

# ── 6. CORS 双向验证 ─────────────────────────────────
# 页面能开、接口全 403 就是这里配错；白名单过宽则是安全漏洞
C=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H 'Origin: https://evil.example' "$BASE/api/blog/stats")
[ "$C" = "403" ] && pass "陌生 Origin 被拒（403）" || fail "陌生 Origin 返回 ${C}，应为 403"

# 自身 Origin 优先用调用方给的（内网部署时访问地址和 env 里的可能不同）
OWN="${OWN_ORIGIN:-$(sed -n 's/^CORS_ALLOWED_ORIGINS=//p' "$ENV_FILE" 2>/dev/null | cut -d, -f1)}"
OWN="${OWN:-$BASE}"
C=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "Origin: $OWN" "$BASE/api/blog/stats")
[ "$C" = "200" ] && pass "自身 Origin（${OWN}）通过（200）" || fail "自身 Origin 返回 $C"

# ── 7. 敏感路径 ──────────────────────────────────────
for P in /.env /.git/config /actuator/health /wp-login.php; do
  C=$(code "$BASE$P" 8)
  case "$C" in
    403 | 404) pass "$P 被拦（${C}）" ;;
    *) fail "$P 返回 ${C}，应被拦成 404" ;;
  esac
done

# ── 8. 限流（edge.conf 的 limit_req：20r/s burst 40）────
# 必须**并发**打：顺序 curl 每个要 30~50ms，90 个摊到 4 秒只有 20r/s，
# 刚好压在阈值上，同一份规则有时报 429 有时不报，看着像规则时灵时不灵。
CODES=$(seq 90 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -m 5 "$BASE/api/blog/stats")
N429=$(printf '%s\n' "$CODES" | grep -c '^429$' || true)
N200=$(printf '%s\n' "$CODES" | grep -c '^200$' || true)
if [ "${N429:-0}" -gt 0 ] && [ "${N200:-0}" -gt 0 ]; then
  pass "限流生效（并发 90 次：$N200 个 200 / $N429 个 429）"
elif [ "${N429:-0}" -gt 0 ]; then
  fail "全部被限流（$N429 个 429），额度可能设太紧"
else
  fail "一次 429 都没有，limit_req 没生效"
fi

# ── 9. 静态资源：入口 JS + 图片 ────────────────────────
ENTRY=$(curl -s -m 10 "$BASE/" | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1)
if [ -n "$ENTRY" ]; then
  C=$(code "$BASE/$ENTRY")
  pass "首页入口 JS 存在（${ENTRY}，${C}）"
  # 带 hash 的资源要长缓存，index.html 要 no-cache，否则「重建了页面还是旧的」
  CACHE=$(curl -sI -m 10 "$BASE/$ENTRY" | grep -i '^cache-control' | tr -d '\r')
  case "$CACHE" in
    *max-age* | *immutable*) pass "静态资源长缓存：$CACHE" ;;
    *) fail "静态资源缓存头异常：${CACHE:-无}" ;;
  esac
else
  fail "首页没解析到入口 JS"
fi

# ── 9b. 图片：路径里全是中文，必须百分号编码 ───────────────
# 踩过的坑：直接拿中文原字节拼 URL，Tomcat 会回 400（"Invalid character
# found in the request target"），看起来像「图片挂了」，其实是请求就没发对。
# 另外 storage/assets 下面第一层是目录（Java/、c++/…），不能 ls | head -1。
IMG=""
for f in $(find storage/assets -type f 2>/dev/null | head -200); do
  # 含空格的路径会被 for 拆成多段，拆出来的碎片不是文件，跳过
  [ -f "$f" ] || continue
  rel=${f#storage/assets/}
  # 优先挑纯 ASCII 且不含空格的路径，省掉编码这一步
  case "$rel" in
    *[!A-Za-z0-9._/+@-]*) continue ;;
    *) IMG="$rel" && break ;;
  esac
done
IMG_ENC=$IMG
if [ -z "$IMG" ] && command -v python3 >/dev/null 2>&1; then
  IMG=$(find storage/assets -type f 2>/dev/null | head -1)
  IMG=${IMG#storage/assets/}
  [ -n "$IMG" ] && IMG_ENC=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$IMG")
fi

if [ -n "$IMG" ]; then
  RESP=$(curl -s -o /dev/null -w '%{http_code} %{content_type}' -m 10 "$BASE/api/assets/$IMG_ENC")
  case "$RESP" in
    200\ image/*) pass "图片可读（${IMG}，200 image）" ;;
    *) fail "图片返回 ${RESP}（路径：${IMG}）" ;;
  esac
else
  printf '\033[33m!\033[0m 跳过图片检查（storage/assets 为空）\n'
fi

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m全部通过：%d 项\033[0m\n' "$PASS"
  exit 0
fi
printf '\033[31m%d 项失败 / %d 项通过\033[0m\n' "$FAIL" "$PASS"
exit 1
