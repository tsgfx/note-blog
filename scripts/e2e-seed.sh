#!/bin/sh
# 冒烟测试的种子数据：建三篇带标签的笔记，发布其中一篇。
#
# 用法：
#   E2E_ADMIN_PASSWORD=xxx sh scripts/e2e-seed.sh [BASE_URL]
#   BASE_URL 默认 http://localhost:8088（容器里 nginx 那一层），
#   直接打后端就传 http://localhost:8081。
#
# 为什么要种子：冒烟用例断言「列表有行、标签能筛出子集、详情正文渲染」，
# 空库时这些用例不是失败而是没东西可点，等于白跑。
#
# 幂等：先按标题里的标记词查一遍，已经种过就直接返回，重跑不会堆重复数据。
set -eu

BASE=${1:-${E2E_BASE_URL:-http://localhost:8088}}
USERNAME=${E2E_ADMIN_USER:-admin}
PASSWORD=${E2E_ADMIN_PASSWORD:?需要 E2E_ADMIN_PASSWORD}

# 三篇共用一个标记词，既用来做幂等判断，也方便之后手工清理
MARKER=冒烟种子

# 从 stdin 的 JSON 里取一层字段；取不到就把响应原文带出来，
# 否则只会看到一句 KeyError，不知道是登录失败还是接口 500。
field() {
    python3 -c '
import json, sys

path = sys.argv[1].split(".")
raw = sys.stdin.read()
try:
    value = json.loads(raw)
    for key in path:
        value = value[key]
except Exception:
    sys.exit("取不到 %s，响应：%s" % (sys.argv[1], raw[:300]))
print(value)
' "${1}"
}

post_json() {
    curl -fsS -X POST "${BASE}${1}" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H 'Content-Type: application/json' \
        --data-binary @-
}

echo "登录 ${BASE} ..."
TOKEN=$(curl -fsS -X POST "${BASE}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" | field data.token)

SEEDED=$(curl -fsS -G "${BASE}/api/notes" \
    --data-urlencode "q=${MARKER}" \
    -H "Authorization: Bearer ${TOKEN}" | field data.totalElements)
if [ "${SEEDED}" != "0" ]; then
    echo "已有 ${SEEDED} 篇种子笔记，跳过"
    exit 0
fi

# 标题按创建顺序递增，于是「最近改动」倒序时最后一篇在最前面；
# 标签刻意做成 git×2 + sql×1 —— 点第一个标签（git）后首行必须换成另一篇，
# 冒烟用例正是靠「首行文字变了」判断筛选真的生效，而不是只换了标签样式。
create() {
    python3 -c '
import json, sys

print(json.dumps({
    "title": sys.argv[1],
    "summary": "冒烟测试种子数据，可安全删除",
    "contentMd": sys.argv[3],
    "tags": sys.argv[2].split(","),
}, ensure_ascii=False))
' "${1}" "${2}" "${3}" | post_json /api/notes
}

echo "建种子笔记 ..."
create "${MARKER} 一 · 分支的代价" git "## 分支\n\n每次 rebase 都在改写历史，合作分支上不要这么做。" >/dev/null
create "${MARKER} 二 · 回滚的方式" git "## 回滚\n\nreset 改历史，revert 留记录。" >/dev/null
LAST=$(create "${MARKER} 三 · 索引选择" sql "## 索引\n\n区分度低的列建索引，优化器多半不会用。" | field data.id)

echo "发布一篇（让公开博客页有内容）：id=${LAST}"
post_json "/api/notes/${LAST}/publish" </dev/null >/dev/null

echo "种子完成：3 篇（1 篇已发布）"
