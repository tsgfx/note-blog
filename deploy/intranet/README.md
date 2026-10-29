# 校园内网部署

目标：把笔记站放到实验室/学校的服务器上，**只对校园网段开放，不接公网**。
不需要域名、不需要备案、不需要证书，配置文件仍然全在仓库里。

## 和公网版的区别

| | 公网版（`deploy/README.md`） | 内网版 |
|---|---|---|
| 入口 | Caddy（自动 TLS） | 前端 nginx 直接对外，明文 HTTP |
| 地址 | `https://你的域名` | `http://内网IP`（或学校内网主机名） |
| HTTPS | Let's Encrypt | 无（如需见文末 Tailscale 一节） |
| 防火墙 | ufw 只开 22/80/443 | ufw 只放行**校园网段** |
| 备案 | 国内机器必须 | 不需要 |

编排文件复用 `docker-compose.prod.yml`，只在上面叠一层
`deploy/intranet/override.yml`（跳过 Caddy + 暴露前端端口）。

## 三步上线

```sh
# 1. 变量：把模板里的 10.20.30.40 换成服务器的真实内网地址
cp deploy/intranet/.env.example .env && vi .env

# 2. 起服务（首次构建 10~20 分钟：Maven 拉依赖 + npm 装包 + 拉 bge-m3）
sh deploy/intranet/up.sh

# 3. 只允许校园网段访问（SSH 与应用端口）
sudo APP_PORT=80 CAMPUS_CIDR="10.0.0.0/8" sh deploy/intranet/firewall.sh
```

`up.sh` 结尾会自动跑 `deploy/rehearsal/check.sh`（16 项：登录、检索、限流、CORS、图片）。

## 三个内网特有的坑

1. **`BIND_IP` 必须绑内网网卡**。Docker 发布的端口会**绕过 ufw**（走 DOCKER-USER 链，
   不经 INPUT 链），所以在 ufw 里「只放行校园网段」是假的安全——端口照样对外开着。
   服务器如果同时有公网 IP，务必在 `.env` 里设 `BIND_IP=10.20.30.40`，让监听只落在内网网卡上。
   只有内网 IP 的机器可以留空（默认 `0.0.0.0`）。
2. **`SITE_BASE_URL` 和 `CORS_ALLOWED_ORIGINS` 要写别人能访问的地址**。写成
   `localhost` 的后果是：你自己在本机能打开，同学打开就「页面正常、所有接口 403」。
   用主机名访问就把主机名也列进白名单（逗号分隔）。
3. **80 端口可能被占用**。学校服务器常有 nginx/Apache 在跑，改用 `APP_PORT=8080`
   （或让它反代），别直接抢。
4. **别和别的栈共用同一个内网网段**。如果你的服务器上已经跑着别的 compose 项目，
   给这套设一个不同的 `INTERNAL_SUBNET`：两套共用 172.28.0.0/24 时，容器会挤进同一个网络，
   DNS 别名 `backend` 同时解析到两边的后端，请求随机打到另一个——
   现象是「登录成功、所有接口 401」。

## 校外访问：三条路，按需求选

内网部署天生「出了校门就够不着」，但有救，成本从零到十块钱一个月：

### 路线 A：Tailscale（零成本，先试这个）

两端装上就能用 `http://100.x.y.z` 访问，不暴露任何公网端口：

```sh
curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
```

装完**先实测速度**：`tailscale status` 看 peer 那一行——

- 显示 `direct` → 打洞成功，速度就是家庭/校园宽带的真实速度，看笔记毫无压力，到此为止
- 显示 `relay "tok"` 之类 → 走了境外中继（Tailscale 在大陆没有 DERP 节点），会很慢，
  这时换路线 B 或 C

Tailscale 的额外好处：IP 固定（校园网换地址不用改配置）、
`sudo tailscale cert` 能签真证书、比校园网段更私密。

装了之后把 `http://<机器名>.<tailnet>.ts.net` 加进 `CORS_ALLOWED_ORIGINS`。

### 路线 B：IPv6 直连（零成本，速度快，教育网特别合适）

教育网 IPv6 覆盖极好，家庭宽带现在也基本都带公网 IPv6——两端都有全球地址时**不需要任何
中转**，速度是真实带宽：

1. 服务器拿到 IPv6 地址（`ip -6 addr` 里出现 `2xxx:` 开头的全局地址）
2. 装 [ddns-go](https://github.com/jeessy2/ddns-go) 之类把地址同步到 DNS（学校内网 DNS
   不行就用免费域名服务；没有域名可以先用 `http://[IPv6地址]:8091` 裸访问）
3. ufw 放行：`sudo ufw allow from <你家宽带的IPv6前缀>/64 to any port 8091 proto tcp`
   —— 不要对全网开放，家用宽带的 IPv6 前缀虽然会变，但一般按月稳定
4. `.env` 里 `CORS_ALLOWED_ORIGINS` 把 `http://[...]:8091` 加上

注意：这条路的暴露面比 Tailscale 大（端口在 IPv6 互联网上可达），JWT + 限流 + fail2ban
都在，风险可控，但别把管理员密码设成弱密码。

### 路线 C：frp 反代到一台最便宜的学生机（约 10 元/月）

**注意：在学校公网口上挂任何服务（frp / 自建 DERP）都被否决了**——会被扫描、探测、
攻击，出事连累全校服务器，安全上不可接受。frp 只能挂在自己租的 VPS 上，
但那样不如直接用 `deploy/README.md` 的公网包（Oracle 免费机或学生机），
内网栈留作备份/离线环境。两条路文档都是现成的。

### 真正的正解：IPv6 直连（不开放任何端口，无攻击面）

家里和学校**两端都是对称 NAT**（netcheck 双双 `MappingVariesByDepIP: true`），
IPv4 打洞无解，只能永远走境外 DERP 中继（实测 RTT ~150ms、隧道内抖到秒级）。
IPv6 没有这个问题——v6 地址全球唯一，**不需要 NAT、不需要打洞、不需要开端口**：

- 学校侧：只有**出站** UDP，校园防火墙上没有任何端口映射、没有可扫的服务；
  WireGuard 对未认证包静默丢弃，不存在「被当挖矿机劫持」的入口
- 家庭宽带和教育网都有公网 v6，两端各开一次就好

操作步骤：

1. 学校服务器 `ip -6 addr` 确认有 `2xxx:` 全局地址（实验室路由器开 IPv6，
   或找网管要前缀；教育网 v6 是标配）
2. 家里路由器管理页打开 IPv6 开关（拨号方式选 DHCPv6/SLAAC）
3. 两端各跑 `tailscale netcheck`，都显示 `IPv6: yes` 后 Tailscale **自动直连**，
   无需任何配置，延迟变成家—学校真实 RTT（20~60ms）

### 怎么选

| 校外使用频率 | 建议 |
|---|---|
| 偶尔查一下笔记 | A（Tailscale），直连就不慢 |
| 每天都用，要快 | B（IPv6 直连） |
| 高频 + 想要正经 HTTPS 站点 | 直接公网部署（`deploy/README.md`） |

## 共享服务器的注意事项

学校服务器往往不止你一个人用，多留意三点：

- **别用 root 跑**：把自己加进 `docker` 组（`sudo usermod -aG docker $USER`，重新登录生效）
- **路径别冲突**：默认会占用 `./storage/assets`、`./backups` 和 named volume。
  要和其他人共用一台机器，先 `git clone` 到自己的目录，端口也换一个（APP_PORT=8081）
- **数据要能拿回来**：备份默认落在服务器本地同一块盘上，等于没备份。
  建议定时同步回本机：`rsync -avz 用户名@服务器:/项目目录/backups ~/note-blog-backups/`

## 数据怎么搬上去

图片目录 2236 张约 240MB，笔记库 22MB 压缩包：

```sh
rsync -avz storage/assets 用户名@服务器:/项目目录/storage/
rsync -avz backups/notes_blog_*.sql.gz 用户名@服务器:/项目目录/backups/
```

库里的数据恢复（空库时 `db/schema.sql` 会先建表，再用备份覆盖）：

```sh
gunzip -c backups/notes_blog_20261023_201123.sql.gz |
  docker compose -f docker-compose.prod.yml -f deploy/intranet/override.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
```

## 日常运维

```sh
# 用环境变量省掉一长串 -f
export COMPOSE_FILE=docker-compose.prod.yml:deploy/intranet/override.yml
docker compose ps
docker compose logs -f backend
docker compose down          # 数据在 volume 里，不会丢
```

更新：本地 `git push` → 服务器 `git pull` → `sh deploy/intranet/up.sh`（带 `--build`）。
改了规则文件（`deploy/nginx/edge.conf`）只需要 `docker compose restart frontend`。
