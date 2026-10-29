#!/bin/sh
# 在 ECS 上启用主机加固：ufw + fail2ban + sshd，并把**当前操作者 IP** 预先加进白名单。
#
# 为什么单独包一层：
#   deploy/apply.sh 是通用入口，交互式确认靠键盘；而且 fail2ban 的 aggressive 模式
#   对本机反复试连（部署脚本、CI）也可能算失败。远程一次性执行时，先把操作者 IP
#   写进 ignoreip，再 FORCE=1 跑 apply.sh —— 既不用交互，也不会把自己关在门外。
#
# 用法（在你自己的终端跑，不要在自动化会话里跑）：
#   ssh -i ~/.ssh/<你的密钥> root@<你的服务器> 'sh /opt/note-blog/deploy/apply-cloud.sh'
#
#   想指定白名单 IP：加 IGNORE_IP=1.2.3.4
#   只想看会做什么、不真执行：加 DRY_RUN=1
#
# 出事了怎么退（都能从阿里云控制台的 VNC「远程连接」进去执行）：
#   1) 关防火墙：      ufw disable
#   2) 撤 fail2ban：    rm -f /etc/fail2ban/jail.d/note-blog.local /etc/fail2ban/jail.local
#                      systemctl restart fail2ban
#   3) 撤 SSH 加固：    rm -f /etc/ssh/sshd_config.d/99-note-blog.conf && systemctl reload ssh
#                      （原文件备份在 /etc/ssh/sshd_config.note-blog.bak）
set -eu

ROOT_DIR="${1:-/opt/note-blog}"
DRY_RUN="${DRY_RUN:-0}"

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

[ "$(id -u)" = "0" ] || die "需要 root：ssh root@... 'sh deploy/apply-cloud.sh'"
[ -f "$ROOT_DIR/deploy/apply.sh" ] || die "找不到 $ROOT_DIR/deploy/apply.sh（第一个参数传仓库目录）"

# ── 1. 确认 sshd 真实端口 ────────────────────────────────
# 端口填错是这套操作里唯一能真正锁死人的地方，所以不猜、直接问 sshd 本身
SSH_PORT=$(sshd -T 2>/dev/null | sed -n 's/^port //p' | head -1)
SSH_PORT="${SSH_PORT:-22}"
if ! ss -ltn 2>/dev/null | grep -q ":${SSH_PORT} "; then
  die "sshd 配置的端口 ${SSH_PORT} 没有在监听，停下来人工确认"
fi
ok "sshd 监听端口：${SSH_PORT}"

# ── 2. 操作者 IP 白名单 ──────────────────────────────────
# SSH_CLIENT 形如 "1.2.3.4 51234 22"；从控制台 VNC 进来时为空
OP_IP="${IGNORE_IP:-$(printf '%s' "${SSH_CLIENT:-}" | awk '{print $1}')}"
FAIL2BAN_LOCAL=/etc/fail2ban/jail.local

if [ -n "$OP_IP" ]; then
  info "把操作者 IP ${OP_IP} 写入 fail2ban 白名单（${FAIL2BAN_LOCAL}）"
  if [ "$DRY_RUN" = "1" ]; then
    info "[dry-run] 跳过写入"
  else
    mkdir -p /etc/fail2ban
    [ -f "$FAIL2BAN_LOCAL" ] && cp "$FAIL2BAN_LOCAL" "${FAIL2BAN_LOCAL}.bak.$(date +%Y%m%d%H%M%S)"
    cat >"$FAIL2BAN_LOCAL" <<EOF
# 由 deploy/apply-cloud.sh 生成：本机登录来源 IP 不参与封禁。
# 部署脚本、CI、自动化探针会反复连 SSH，撞上 fail2ban 的 maxretry 就直接把自己封 1 小时。
# 换网络环境（IP 变了）后重跑一次这个脚本即可。
[DEFAULT]
ignoreip = 127.0.0.1/8 ::1 ${OP_IP}
EOF
    ok "白名单已写入"
  fi
else
  warn "拿不到操作者 IP（不是从 SSH 会话进来的？），跳过 ignoreip"
  warn "如果之后 IP 被 fail2ban 封了：fail2ban-client set sshd unbanip <你的IP>"
fi

# ── 3. 调通用入口 ────────────────────────────────────────
info "执行 deploy/apply.sh（FORCE=1，跳过交互确认）"
if [ "$DRY_RUN" = "1" ]; then
  info "[dry-run] 跳过实际执行。真实执行会：reset ufw → 放行 ${SSH_PORT}/80/443 → enable"
  info "[dry-run] 然后应用 sshd 加固并 reload，最后装 jail 并重启 fail2ban"
  exit 0
fi

cd "$ROOT_DIR"
FORCE=1 SSH_PORT="$SSH_PORT" sh deploy/apply.sh

# ── 4. 复查 ─────────────────────────────────────────────
printf '\n'
info "复查"
ufw status verbose | sed -n '1,12p'
printf '\n'
fail2ban-client status sshd || warn "sshd jail 未就绪，看 journalctl -u fail2ban -n 50"

printf '\n'
ok "主机加固完成"
warn "现在开一个新终端验证还能登进来，再关掉当前这个："
# 不能用 hostname -I：ECS 上它返回内网地址（172.16.x），照抄连不上。
# 公网 IP 从阿里云元数据服务取；取不到就让操作者自己填。
PUBLIC_IP=$(curl -s --max-time 3 http://100.100.100.200/latest/meta-data/eipv4 2>/dev/null || true)
[ -n "$PUBLIC_IP" ] || PUBLIC_IP='<公网IP>'
printf '    ssh -i ~/.ssh/<你的密钥> -o ConnectTimeout=8 root@%s "echo ok"\n' "$PUBLIC_IP"
warn "进不来就用阿里云控制台的 VNC 远程连接执行：ufw disable"
info "别忘了云控制台的安全组也同步成 ${SSH_PORT}/80/443 三层一致"
