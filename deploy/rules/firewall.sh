#!/bin/sh
# 主机防火墙规则 —— 这就是规则的唯一来源，进 git，不靠控制台点。
#
#   sudo SSH_PORT=22 sh deploy/rules/firewall.sh
#   sudo SSH_PORT=22 FORCE=1 sh deploy/rules/firewall.sh    # 跳过确认
#
# 设计：默认拒绝所有入站，只放 22（SSH，带速率限制）、80、443。
# 出站全开（要拉镜像、调 LLM、签发证书）。
#
# ⚠️ 两个必须知道的前提：
#   1. 云厂商控制台的「防火墙/安全组」是独立一层，ufw 管不到它。
#      那边也要只开 22/80/443；两层的交集才是真正对外开放的端口。
#   2. **Docker 发布的端口会绕过 ufw**（走 DOCKER-USER 链，不走 INPUT）。
#      所以生产 compose 里 mysql/ollama/backend 一律不 publish 端口，
#      否则 ufw 显示 3306 拒绝，实际公网照样连得上。
#      自查：sh deploy/verify.sh 会从外部探测 8081/8088/3306。

set -eu

SSH_PORT="${SSH_PORT:-22}"
FORCE="${FORCE:-0}"

die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}
info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }

[ "$(id -u)" = "0" ] || die "需要 root：sudo sh deploy/rules/firewall.sh"

command -v ufw >/dev/null 2>&1 || {
  info "没装 ufw，尝试安装…"
  (apt-get update -qq && apt-get install -y -qq ufw) || die "安装 ufw 失败，请手动 apt install ufw"
}

# 防手滑：改 SSH 端口是最容易把自己锁在门外的一步
if [ "$FORCE" != "1" ]; then
  printf '将重置 ufw 规则：默认拒绝入站，只放 %s/tcp(限速)、80/tcp、443/tcp+udp。\n' "$SSH_PORT"
  printf '确认当前 SSH 端口就是 %s ？[y/N] ' "$SSH_PORT"
  read -r ANSWER
  case "$ANSWER" in
    y | Y | yes | YES) ;;
    *) die "已取消。端口不同请用 SSH_PORT=你的端口 重跑" ;;
  esac
fi

info "重置旧规则"
# reset 会先把 ufw 停掉，此时不会锁死；下面按顺序重新建好再 enable
ufw --force reset >/dev/null

info "默认策略：入站拒绝 / 出站放行"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null

info "放行 SSH（$SSH_PORT/tcp，限速：同 IP 30 秒内超过 6 次新连接会拒绝）"
ufw limit "${SSH_PORT}/tcp" comment 'ssh (rate limited)' >/dev/null

info "放行 HTTP/HTTPS"
ufw allow 80/tcp comment 'http (acme + redirect)' >/dev/null
ufw allow 443/tcp comment 'https' >/dev/null
# HTTP/3 走 UDP，Caddy 默认会尝试；不开也不影响，只是退回 h2
ufw allow 443/udp comment 'https (http/3)' >/dev/null

info "启用"
ufw --force enable >/dev/null

printf '\n'
ufw status verbose
printf '\n'
ok "防火墙已就绪：对外只有 22/80/443"
info "别忘了云控制台的防火墙/安全组也同步成 22/80/443"
