#!/bin/sh
# 把最新一份数据库备份加密后传到腾讯云 COS（异地副本）。
#
#   sh scripts/backup-upload-cos.sh            # 由 backup-cloud.sh 自动调用
#
# 设计取舍：
#   - 不用「服务器推、本机拉」：拉取需要 Mac 每周醒来一次，是最后一条生产依赖。
#     服务器备份完直接上传，Mac 可以长期不开。
#   - 对象先做 AES-256 加密再上传，所以可以放进现有的**公有读**图片桶：
#     即使桶是公开的，没有密钥也解不开 dump（里面有笔记正文与密码哈希）。
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

say "上传到 cos://${COS_BUCKET}/${COS_BACKUP_PREFIX}${NAME}"
python3 "$ROOT/scripts/cos-upload.py" \
  --dir "$TMP" --bucket "$COS_BUCKET" --region "$COS_REGION" \
  --prefix "$COS_BACKUP_PREFIX" --workers 1

# 恢复方式（把 .enc 解回 SQL）：
#   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
#     -in notes_blog_XXX.sql.gz.enc -pass file:/opt/note-blog/.backup-key \
#     | gunzip | mysql -u root -p notes_blog
say "完成"
