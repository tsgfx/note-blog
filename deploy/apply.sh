#!/bin/sh
# 把 deploy/rules/ 下的规则应用到这台机器（幂等，可以反复跑）
#
#   sudo sh deploy/apply.sh              # 全套：防火墙 + SSH 加固 + fail2ban
#   sudo SSH_PORT=22 sh deploy/apply.sh
#   sudo sh deploy/apply.sh --skip-ssh   # 只做防火墙和 fail2ban
#
# 只支持 Debian/Ubuntu（ufw、apt、sshd_config.d 都在假定里）。
# CentOS 系请照 rules/ 里的内容手动对应：firewalld / sshd_config / fail2ban。

set -eu

SKIP_SSH=0
[ "${1:-}" = "--skip-ssh" ] && SKIP_SSH=1

SSH_PORT="${SSH_PORT:-22}"

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

info() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }
die() {
  printf '\033[31m✗\033[0m %s\n' "$1" >&2
  exit 1
}

[ "$(id -u)" = "0" ] || die "需要 root：sudo sh deploy/apply.sh"

# ── 依赖 ──────────────────────────────────────────────
info "检查依赖"
# 用 dpkg 判断包是否装了，不能看命令名也不能看 /etc 目录：
# fail2ban 包没有名为 fail2ban 的命令（只有 fail2ban-client），
# 而 /etc/fail2ban 可能先被白名单脚本建出来——两者都会把「没装」判成「就绪」。
for PKG in ufw fail2ban; do
  if ! dpkg -s "$PKG" >/dev/null 2>&1; then
    info "安装 $PKG"
    apt-get update -qq && apt-get install -y -qq "$PKG" || die "安装 $PKG 失败"
  fi
done
ok "ufw / fail2ban 就绪"

# ── 防火墙 ────────────────────────────────────────────
info "应用防火墙规则"
if [ "${FORCE:-0}" = "1" ]; then
  SSH_PORT="$SSH_PORT" FORCE=1 sh deploy/rules/firewall.sh
else
  # 交互式确认由 firewall.sh 自己问
  SSH_PORT="$SSH_PORT" sh deploy/rules/firewall.sh
fi

# ── SSH 加固 ──────────────────────────────────────────
if [ "$SKIP_SSH" = "1" ]; then
  warn "跳过 SSH 加固（--skip-ssh）"
else
  info "应用 SSH 加固"
  if [ ! -d /etc/ssh/sshd_config.d ]; then
    die "本机 sshd 不支持 Include 目录（sshd_config.d 不存在），请手动把 deploy/rules/sshd-hardening.conf 的内容合并进 /etc/ssh/sshd_config"
  fi

  # 先备份一次，出事能退回去
  if [ ! -f /etc/ssh/sshd_config.note-blog.bak ]; then
    cp /etc/ssh/sshd_config /etc/ssh/sshd_config.note-blog.bak
  fi

  install -m 0644 deploy/rules/sshd-hardening.conf /etc/ssh/sshd_config.d/99-note-blog.conf

  # 语法校验：真有问题就别 reload，否则下次断开就再也连不上
  if ! sshd -t 2>/tmp/sshd-check.err; then
    printf '\n'
    cat /tmp/sshd-check.err >&2
    rm -f /etc/ssh/sshd_config.d/99-note-blog.conf
    die "sshd 配置语法错误，已回滚。见上面的报错"
  fi
  ok "sshd 配置语法 OK"

  systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || die "reload sshd 失败，手动执行 systemctl reload ssh"
  ok "ssh 已 reload（现有连接不受影响，新连接开始生效）"
  warn "下次登录必须是密钥：先开一个新终端确认能登录，再关掉当前这个"
fi

# ── fail2ban ─────────────────────────────────────────
info "应用 fail2ban 规则"
install -m 0644 deploy/rules/jail.local /etc/fail2ban/jail.d/note-blog.local
systemctl enable fail2ban >/dev/null 2>&1 || true
systemctl restart fail2ban || die "fail2ban 启动失败，journalctl -u fail2ban -n 50 看原因"
sleep 1
fail2ban-client status sshd || warn "sshd jail 没起来，检查 jail.local 里的 backend/banaction"

printf '\n'
ok "主机加固完成"
info "复查：sudo ufw status verbose && sudo fail2ban-client status sshd"
info "下一步：sh deploy/up.sh 部署应用"
