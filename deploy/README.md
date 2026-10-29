# 云端部署（性价比 + 安全，规则全部在仓库里）

目标：一台便宜的小机器跑起来，对外只暴露 22/80/443，所有防火墙/限流/黑名单规则都是
**本仓库里的文件**，本地改、`git push`、服务器 `git pull` 就生效——不靠云控制台点按钮。

## 一、选型结论

| 项 | 选择 | 价格参考 | 理由 |
| --- | --- | --- | --- |
| 服务器 | 腾讯云/阿里云 轻量应用服务器 **4核8G** | 新用户 600~700 元/年 | 同一台要跑 MySQL + JVM + Ollama(bge-m3)。2核2G 会被 OOM 杀掉后端 |
| 系统 | Ubuntu 24.04 LTS | 免费 | ufw / fail2ban / sshd_config.d 都是默认有 |
| 域名 | 国内备案站 → 腾讯云/阿里云；免备案 → Cloudflare Registrar | 60~90 元/年 | 看**续费价**，别看首年 1 美元 |
| TLS | Caddy 自动签发续期 | 0 | 比 certbot 少一堆定时任务 |
| 备案 | 国内机器必须 ICP 备案（1~2 周）；香港机器免备案 | 免费 | 想当天上线就选香港 |

> 域名解析建议：A 记录指向服务器公网 IP，`www` 用 CNAME 指到裸域。
> 先解析、再去签证书，顺序反了 Caddy 会一直失败重试。

## 二、这套东西包含什么

```
docker-compose.prod.yml     生产编排：只有 Caddy 对外，其余全在内网
deploy/
├── .env.prod.example       生产变量模板（必填项空着会启动失败）
├── Caddyfile               TLS 终止 + 安全响应头 + 隐藏 Server
├── nginx/edge.conf         应用层规则：限流、UA 黑名单、敏感路径、真实 IP
├── rules/
│   ├── firewall.sh         ufw 规则即代码（只开 22/80/443）
│   ├── sshd-hardening.conf 密钥登录、禁密码、限尝试次数
│   └── jail.local          fail2ban：爆破 5 次封 1 小时
├── apply.sh                把 rules/ 应用到这台机器（幂等）
├── up.sh                   构建启动 + 等健康 + 自动自检
├── pull-deploy.sh          服务器上 git pull + 只重建变更服务（日常发版入口）
└── verify.sh               公网视角自检：该通的通、该关的关
```

与开发版 `docker-compose.yml` 的差异：不 publish 8081/8088/3306/11434；不挂
`~/.claude|.codex|.gemini`（云端没有本机 CLI 凭证）；加日志轮转与 `cap_drop`；
内网固定 `172.28.0.0/24`，让 edge nginx 的 `realip` 只信 Caddy 一个来源。

## 三、首次上线

```sh
# 1. 服务器装 docker（官方脚本，国内可换阿里云镜像源）
curl -fsSL https://get.docker.com | sh

# 2. 拉代码（私有仓库用只读 deploy key；GitHub 在 ECS 上实测可达）
git clone git@github.com:你的账号/note-blog.git ~/note-blog && cd ~/note-blog

# 3. 配变量
cp deploy/.env.prod.example .env && vi .env      # 至少填 DOMAIN/ACME_EMAIL/密码/密钥

# 4. 先加固主机，再部署应用（顺序别反：先开 80/443，证书才签得下来）
sudo sh deploy/apply.sh
sh deploy/up.sh
```

`up.sh` 结束时会自动跑 `verify.sh`，全绿就说明上线完成。

### 已有部署迁移成 git 工作树（一次性）

老服务器上的 `/opt/note-blog` 是 tar/scp 传上去的普通目录，不是 git 仓库。
转成 git 工作树不会动 `.env`、`backups/`、`storage/`、`credentials/` 这些未跟踪文件：

```bash
cd /opt/note-blog
git init
git remote add origin git@github.com:你的账号/note-blog.git
git fetch origin
# 让受控文件与远程一致；未跟踪的 .env / backups / storage 原样保留
git reset --hard origin/main
# 顺便跳过服务器不需要的目录（可选，省得每次 pull 都带下来）
git sparse-checkout init --no-cone
git sparse-checkout set '/*' '!/.workbuddy' '!/shots' '!/.github' '!/docker-compose.yml'
rm -f .deployed-version
```

> `docker-compose.yml`（开发版）被排除是有意的：服务器上只该有生产编排，
> 不带 `-f` 的 `docker compose up` 会拉起错误的栈。

**SSH 加固的注意点**：`apply.sh` 会在 reload 前用 `sshd -t` 校验语法并回滚，
但它没法知道你有没有配好密钥。执行前先在**另一个终端**确认
`ssh -i 你的密钥 user@服务器` 能登录，再关掉当前会话。

**不想拿真机试错**：WorkBuddy 的云端沙箱是 4核8G/250G + 自带 Docker，规格和推荐的
服务器一致，可以先把整套编排真跑一遍（含 Caddyfile 语法校验、限流实测），再去买机器。
步骤见 [REHEARSAL.md](REHEARSAL.md)。注意它空闲会休眠、公网地址是平台子域，
**只适合预演，不能当正式站**。

## 四、规则在哪改（改完怎么生效）

| 想改什么 | 改哪个文件 | 怎么生效 |
| --- | --- | --- |
| 接口限流速率 / 突发 | `deploy/nginx/edge.conf` 顶部 `limit_req_zone` | `docker compose -f docker-compose.prod.yml restart frontend` |
| 挡某个 IP / IP 段 | `edge.conf` 里加 `deny`，或直接 `ufw deny from 1.2.3.4` | frontend restart / 立即 |
| 加扫描器 UA 黑名单 | `edge.conf` 的 `$http_user_agent` 正则 | frontend restart |
| 防火墙端口 | `deploy/rules/firewall.sh` | `sudo sh deploy/apply.sh` |
| SSH 策略 / 登录用户 | `deploy/rules/sshd-hardening.conf` | `sudo sh deploy/apply.sh` |
| 爆破封禁时长 | `deploy/rules/jail.local` | `sudo sh deploy/apply.sh` |
| 安全响应头 / HSTS | `deploy/Caddyfile` | `docker compose -f docker-compose.prod.yml restart caddy` |
| 业务限流（问答/搜索次数） | `.env` 的 `RATE_LIMIT_ASK/SEARCH` | `docker compose -f docker-compose.prod.yml up -d backend` |

改完执行 `sh deploy/verify.sh` 复核，尤其是改限流和白名单之后。

## 五、必须知道的坑

1. **Docker 发布的端口会绕过 ufw**（走 DOCKER-USER 链，不经 INPUT）。所以生产 compose
   里除了 Caddy 谁都不 publish 端口。`verify.sh` 会从外部实测 8080/8081/8088/3306/11434，
   这比看 `ufw status` 可信。
2. **云控制台的防火墙是独立一层**，ufw 管不到。那边也要只开 22/80/443；
   两层的交集才是真正对外暴露的端口。
3. **CORS 白名单必须精确写域名**。浏览器即使同源发 POST 也带 `Origin` 头，白名单不对
   就是「页面能开、所有接口 403」。也**不要**写 IP 通配（`192.168.*`）——Spring 会展成
   正则，形似域名也能过。`verify.sh` 用陌生 Origin 和自身 Origin 各测一次。
4. **前端是构建进镜像的静态文件**。改 `frontend/src` 必须 `up.sh`（带 `--build`），
   只 `restart` 不会更新页面。改 `edge.conf` 例外——它是挂载进去的，restart 即可。
5. **`/api/health` 的 HTTP 码永远 200**，状态在 body 的 `status` 字段（up/degraded）。
   部署脚本读 body，别读状态码。
6. **首次部署要拉 bge-m3（约 1.2GB）**，`up.sh` 会自动补齐；网络慢就多等几分钟，
   拉不下来时 `/api/health` 会显示 degraded（服务能跑，检索/问答不可用）。
7. **备份别只留在同一台机器**。`backup-cloud.sh` 会把 dump 加密传到 COS 的
   `backups/` 前缀；`.backup-key` 丢了就解不开，记得在密码管理器里留一份。
8. **一台机器只跑一套栈**。内网名是 `${COMPOSE_PROJECT_NAME}-internal`，
   同机上起第二套（比如开发栈 + 生产栈）时如果不换 `INTERNAL_SUBNET`，
   容器会挤进同一个网段、DNS 别名同时解析到两边，请求随机打到另一个后端。
   真出了这事的现象是「登录成功、所有接口 401」（两套的 JWT 密钥不同）。
   要并行跑就给第二套设不同的 `INTERNAL_SUBNET`。

## 六、日常运维

```sh
docker compose -f docker-compose.prod.yml ps            # 状态
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml exec ollama ollama list
docker compose -f docker-compose.prod.yml down          # 停（数据在 volume 里）
sh deploy/verify.sh                                     # 体检
```

更新流程：本地 `git push` → 服务器 `cd /opt/note-blog && sh deploy/pull-deploy.sh`。
脚本按 `git diff` 判断哪些服务需要重建，只重建变更的部分；内容没变时直接退出。
本机不再需要参与部署（`scripts/deploy-cloud.sh` 已删除）。

**备份**：服务器上跑 `scripts/backup-cloud.sh`，它固定用生产编排，备份成功后再调
`backup-upload-cos.sh` 把 dump 加密传到 COS。

```sh
# 手动
sh /opt/note-blog/scripts/backup-cloud.sh

# 每天 3 点（crontab -e）
0 3 * * * sh /opt/note-blog/scripts/backup-cloud.sh >> /var/log/notesblog-backup.log 2>&1
```

异地副本需要两个只在服务器上的文件（都不进仓库）：

```sh
# COS 凭证与非敏感配置
cat > /opt/note-blog/.backup-cos.env <<'EOF'
COS_SECRET_ID=...
COS_SECRET_KEY=...
COS_BUCKET=note-blog-img-1324019419
COS_REGION=ap-beijing
COS_BACKUP_PREFIX=backups/
EOF
chmod 600 /opt/note-blog/.backup-cos.env

# 加密口令（丢了就解不开，务必抄一份到密码管理器）
openssl rand -base64 32 > /opt/note-blog/.backup-key
chmod 600 /opt/note-blog/.backup-key
```

没有这两个文件时上传步骤会自行跳过，备份仍留在本机。

恢复异地副本：

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -in notes_blog_XXX.sql.gz.enc -pass file:/opt/note-blog/.backup-key \
  | gunzip | docker compose -f docker-compose.prod.yml exec -T mysql \
      mysql -u root -p"$MYSQL_ROOT_PASSWORD" notes_blog
```

**图片上传 500**：后端在容器里以非 root 用户 `appuser` 运行，Linux 的 bind mount
不会自动改属主，首次部署常见「页面正常、传图失败」。`up.sh` 会检测并提示，修法是：

```sh
sudo chown -R $(docker compose -f docker-compose.prod.yml exec -T backend id -u):$(docker compose -f docker-compose.prod.yml exec -T backend id -g) storage/assets
```

## 七、可选：再挂一层 Cloudflare（免备案方案尤其值）

域名 DNS 托管到 Cloudflare 并开橙云代理后：

- 源站真实 IP 被隐藏，扫描器只看到 CF 的 IP，直接打 IP 的攻击失效
- 免费 DDoS 防护 + 免费 WAF 规则（可在控制台或 Terraform 里配，同样能进 git）
- 代价：国内访问会绕一圈，延迟增加；备案站建议只当 DNS 用（灰云），不要代理

开了橙云后 Caddy 签证书要改 DNS-01 挑战（因为回源 IP 变了），或者把 Cloudflare
的 SSL 模式设为 Full (strict) 并在源站保留 80 端口给 ACME HTTP-01。这两种改法在上线前
先定好，别上线后再改。
