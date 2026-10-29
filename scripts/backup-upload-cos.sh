#!/bin/sh
# 把最新一份数据库备份加密后传到腾讯云 COS（异地副本）。
#
#   sh scripts/backup-upload-cos.sh            # 由 backup-cloud.sh 自动调用
#
# 设计取舍：
#   - 不用「服务器推、本机拉」：拉取需要 Mac 每周醒来一次，是最后一条生产依赖。
#     服务器备份完直接上传，Mac 可以长期不开。
#   - **两层保护，缺一不可**：
#       1. 每个对象显式标 `--acl private`，不依赖桶本身的可见性
#          ——就算桶是公有读的，标了 private 的对象匿名 GET 也是 403；
#       2. 上传前先 AES-256 加密，落盘的是密文。
#     只靠加密是不够的：拿到对象的人可以离线慢慢试，而且是「能下载」这件事本身
#     就不该成立。只靠 private 也不够：误把桶改成公有读就等于全裸。
#   - 强烈建议给备份单独开一个私有桶（COS_BUCKET 填它）。复用图片桶只是
#     图省事，对象级 private 兜住了最坏情况。
#   - 密钥与 COS 凭证单独放 /opt/note-blog/.backup-cos.env 与 .backup-key（600），
#     不进仓库；.env 里只写非敏感配置。
#
# 未配置时静默跳过（exit 0）：本功能是可选的，不该让每日备份因此失败。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${BACKUP_DIR:-$ROOT/backups}"
CONF="${COS_BACKUP_ENV:-/opt/note-blog/.backup-cos.env}"
KEY="${COS_BACKUP_KEY:-/opt/note-blog/.backup-key}"

say() { printf '%s [backup-cos] %s\n' "$(date '+%F %T')" "$*"; }

if [ ! -f "$CONF" ]; then
  say "未配置 ${CONF}，跳过 COS 上传"
  exit 0
fi

# shellcheck disable=SC1090
. "$CONF"

: "${COS_SECRET_ID:?备份配置缺少 COS_SECRET_ID}"
: "${COS_SECRET_KEY:?备份配置缺少 COS_SECRET_KEY}"
: "${COS_BUCKET:?备份配置缺少 COS_BUCKET}"
COS_REGION="${COS_REGION:-ap-beijing}"
COS_BACKUP_PREFIX="${COS_BACKUP_PREFIX:-backups/}"
export COS_SECRET_ID COS_SECRET_KEY

[ -f "$KEY" ] || {
  say "缺少加密密钥 ${KEY}（openssl rand -base64 32 > ${KEY}; chmod 600 ${KEY}）"
  exit 1
}

LATEST=$(ls -1t "$DEST"/notes_blog_*.sql.gz 2>/dev/null | head -1)
[ -n "$LATEST" ] || {
  say "找不到数据库备份（先跑一次 backup.sh）"
  exit 1
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

NAME="$(basename "$LATEST").enc"
say "加密 $(basename "$LATEST") → ${NAME}"
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 \
  -in "$LATEST" -out "$TMP/$NAME" -pass file:"$KEY"

say "上传到 cos://${COS_BUCKET}/${COS_BACKUP_PREFIX}${NAME}（private）"
python3 "$ROOT/scripts/cos-upload.py" \
  --dir "$TMP" --bucket "$COS_BUCKET" --region "$COS_REGION" \
  --prefix "$COS_BACKUP_PREFIX" --workers 1 \
  --acl private --signed-head

# 上传后立刻验证「匿名拿不到」：这是这套方案唯一真正要守的性质。
#   - 公有桶 + private 对象：匿名 HEAD 应回 403
#   - 私有桶：同样 403
# 如果这里回 200，说明 ACL 没生效（或桶策略覆盖了），要当失败处理。
URL="https://${COS_BUCKET}.cos.${COS_REGION}.myqcloud.com/${COS_BACKUP_PREFIX}${NAME}"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -I "$URL" || echo 000)
case "$CODE" in
  403) say "匿名访问校验通过（HTTP 403，未授权读不到）" ;;
  404) say "匿名访问校验：对象不存在？检查上传结果（HTTP 404）" && exit 1 ;;
  000) say "匿名访问校验：网络超时，无法确认；不计失败但请人工复核" ;;
  *)
    say "✗ 匿名可以访问（HTTP ${CODE}），ACL 未生效——备份数据暴露，请立即处理"
    exit 1
    ;;
esac

# 恢复方式（把 .enc 解回 SQL）：
#   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
#     -in notes_blog_XXX.sql.gz.enc -pass file:/opt/note-blog/.backup-key \
#     | gunzip | mysql -u root -p notes_blog
say "完成"
