#!/bin/sh
# 在服务器上部署：git pull → 只重建内容变了的服务 → 等健康。
#
#   sh deploy/pull-deploy.sh
#   sh deploy/pull-deploy.sh --force backend,frontend   # 内容没变也重建
#
# 为什么改成服务器拉取：
#   以前由本机 scripts/deploy-cloud.sh 打包 scp 上云，Mac 是唯一的部署入口；
#   现在代码在 GitHub，服务器自己 pull，本机不开机也能发版。
#   代价是服务器必须能连 GitHub（国内 ECS 实测可达；不通就退回本机推送）。
#
# 前提：/opt/note-blog 是一个 git 工作树，且工作区干净。
# 首次把旧目录转成 git 工作树的步骤见 deploy/README.md。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE="docker-compose.prod.yml"
FORCE_SERVICES=""

while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE_SERVICES="${2:-}"; shift ;;
    --force=*) FORCE_SERVICES="${1#*=}" ;;
    *) printf '未知参数：%s（可用：--force backend,frontend,caddy）\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

[ -d .git ] || die "当前目录不是 git 仓库。首次转换步骤见 deploy/README.md"
[ -f "$COMPOSE" ] || die "找不到 ${COMPOSE}，请在项目根目录执行"

# 手工改过服务器上的受控文件时，pull 会冲突。先停下来说清楚，别半途覆盖。
if ! git diff --quiet || ! git diff --cached --quiet; then
  git status --short >&2
  die "工作区有未提交改动：先确认并提交/还原（服务器上不应该手改受控文件）"
fi

PREV=$(git rev-parse HEAD)
info "拉取 origin"
# 国内到 GitHub 的 TLS 会话会被随机掐断（实测 GnuTLS recv error -110 与
# 133 秒后 Couldn't connect 交替出现，隔几秒重试就通）。一次失败就中止会让
# 部署变成「多试几次」，所以这里自己重试，而不是把抖动留给人工判断。
MAX_PULL_ATTEMPTS="${MAX_PULL_ATTEMPTS:-4}"
try_pull() {
  i=1
  while [ "$i" -le "$MAX_PULL_ATTEMPTS" ]; do
    if git pull --ff-only; then
      return 0
    fi
    [ "$i" -lt "$MAX_PULL_ATTEMPTS" ] || break
    warn "第 ${i} 次拉取失败，$((i * 10)) 秒后重试"
    sleep $((i * 10))
    i=$((i + 1))
  done
  return 1
}

try_pull || die "拉取失败：连续 ${MAX_PULL_ATTEMPTS} 次都连不上 GitHub（网络问题，不是代码问题）
  自查：curl -sI --max-time 10 https://github.com | head -1
  彻底不通时只能从别处推：在本机 git push 后，用 ssh 把裸仓库传上来再 fetch"
NEW=$(git rev-parse HEAD)

if [ "$PREV" = "$NEW" ]; then
  if [ -z "$FORCE_SERVICES" ]; then
    ok "已是最新（$(git rev-parse --short HEAD)），无需重建"
    exit 0
  fi
  CHANGES=""
  info "内容未变，按 --force 重建：${FORCE_SERVICES}"
else
  CHANGES=$(git diff --name-status "$PREV" "$NEW")
  info "更新：$(git log -1 --format='%h %s' "$NEW")"
fi

# 只看路径列：--name-status 每行是「状态<TAB>路径」，匹配行首会永远不命中
PATHS=$(printf '%s\n' "$CHANGES" | awk '{ print $NF }')

SERVICES=""
if printf '%s\n' "$PATHS" | grep -qE '^backend/|^db/|^'"$COMPOSE"'$'; then
  SERVICES="$SERVICES backend"
fi
if printf '%s\n' "$PATHS" | grep -qE '^frontend/|^'"$COMPOSE"'$'; then
  SERVICES="$SERVICES frontend"
fi
# edge.conf 是挂进 frontend 容器的，不在 frontend/ 下，必须显式重启
if printf '%s\n' "$PATHS" | grep -qE '^deploy/nginx/'; then
  SERVICES="$SERVICES frontend"
fi
if printf '%s\n' "$PATHS" | grep -qE '^deploy/Caddyfile$|^'"$COMPOSE"'$'; then
  SERVICES="$SERVICES caddy"
fi
SERVICES=$(printf '%s' "$SERVICES" | sed 's/^ *//')

if [ -n "$FORCE_SERVICES" ]; then
  SERVICES=$(printf '%s' "$FORCE_SERVICES" | tr ',' ' ')
fi

if [ -z "$SERVICES" ]; then
  ok "只改了文档/脚本，无需重建容器"
  exit 0
fi

# 版本号只跟着 backend 走：没重建 backend 却改版本号，会让 /api/health 说谎
if printf '%s' "$SERVICES" | grep -qw backend; then
  SHORT=$(git rev-parse --short HEAD)
  if grep -q '^APP_VERSION=' .env; then
    sed -i "s|^APP_VERSION=.*|APP_VERSION=${SHORT}|" .env
  else
    printf 'APP_VERSION=%s\n' "$SHORT" >>.env
  fi
  ok "APP_VERSION → ${SHORT}"
fi

info "重建：${SERVICES}（首次构建可能要几分钟）"
# shellcheck disable=SC2086
docker compose -f "$COMPOSE" up -d --build $SERVICES

info "等待服务就绪"
I=0
while [ "$I" -lt 40 ]; do
  HEALTH=$(curl -s --noproxy '*' --max-time 5 http://127.0.0.1/api/health || true)
  if printf '%s' "$HEALTH" | grep -q '"status":"up"'; then
    ok "后端 up"
    printf '%s\n' "$HEALTH" | sed 's/^/  /'
    break
  fi
  I=$((I + 1))
  sleep 3
done
[ "$I" -lt 40 ] || die "等了两分钟还没 up，看 docker compose -f ${COMPOSE} logs backend"

printf '\n'
ok "部署完成：$(git rev-parse --short HEAD)"
