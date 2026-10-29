#!/bin/sh
# 内网版防火墙：只允许校园网段访问 SSH 与应用端口
#
#   sudo APP_PORT=80 CAMPUS_CIDR="10.0.0.0/8 172.16.0.0/12" sh deploy/intranet/firewall.sh
#   sudo FORCE=1 ... sh deploy/intranet/firewall.sh     # 跳过确认
#
# ⚠️ 两个必须读的提醒：
#   1. **先确认你从哪儿 SSH**。如果白名单里没有你当前的来源网段，脚本跑完你就被锁在外面了。
#      要从校外管，先把 Tailscale 装好（见 README），或临时把你的公网 IP 加进 CAMPUS_CIDR。
#   2. **ufw 管不住 Docker 发布的端口**。前端端口靠 `BIND_IP` 绑在内网网卡上（见 override.yml），
#      ufw 那一层只是兜底。机器如果同时有公网 IP，务必设置 BIND_IP。

set -eu

SSH_PORT="${SSH_PORT:-22}"
APP_PORT="${APP_PORT:-80}"
# 默认覆盖 RFC1918 三段私有地址；学校用公网段（比如 202.x/16）时必须显式传
CAMPUS_CIDR="${CAMPUS_CIDR:-10.0.0.0/8 172.16.0.0/12 192.168.0.0/16}"
FORCE="${FORCE:-0}"

die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}
info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }

[ "$(id -u)" = "0" ] || die "需要 root：sudo sh deploy/intranet/firewall.sh"

command -v ufw >/dev/null 2>&1 || {
  info "安装 ufw"
  (apt-get update -qq && apt-get install -y -qq ufw) || die "安装 ufw 失败"
}

if [ "$FORCE" != "1" ]; then
  printf '将重置 ufw：默认拒绝入站，只允许以下网段访问 SSH(%s) 与 %s 端口：\n  %s\n' \
    "$SSH_PORT" "$APP_PORT" "$CAMPUS_CIDR"
  printf '⚠️  如果你从不在上面这些网段里，跑完会失去 SSH。确认继续？[y/N] '
  read -r ANSWER
  case "$ANSWER" in
    y | Y | yes | YES) ;;
    *) die "已取消" ;;
  esac
fi

info "重置规则"
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null

info "按网段放行"
for CIDR in $CAMPUS_CIDR; do
  ufw allow from "$CIDR" to any port "$SSH_PORT" proto tcp comment "ssh from $CIDR" >/dev/null
  ufw allow from "$CIDR" to any port "$APP_PORT" proto tcp comment "app from $CIDR" >/dev/null
  ok "$CIDR → SSH:$SSH_PORT / APP:$APP_PORT"
done

ufw --force enable >/dev/null

printf '\n'
ufw status verbose
printf '\n'
ok "只对内网开放"
info "自查：从宿舍/办公室的机器 curl http://<内网IP>:$APP_PORT/api/health，应返回 status"
info "Docker 端口绕过 ufw，务必确认 override.yml 里 BIND_IP 绑的是内网网卡"
