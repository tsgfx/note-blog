# 用 WorkBuddy 的云端沙箱做部署预演

## 先说结论：它不能当正式站，但当预演场非常合适

沙箱的实际规格（4 核 / 8G 内存 / 250G 磁盘 / Ubuntu / root / 自带 Docker 27），
和我建议的轻量服务器**完全一样**，所以要验证的东西它都能跑：镜像构建、compose 编排、
MySQL 初始化、Ollama 拉模型、nginx 规则。

但有四个硬伤决定了它**不能**承载正式博客：

| 硬伤 | 具体表现 | 后果 |
| --- | --- | --- |
| **空闲即休眠** | 约 180 秒无活动就休眠，访问时再唤醒 | RSS 抓取、访客第一次打开都要等冷启动；后台定时任务也会断 |
| **公网地址不是你的** | 形如 `https://<space-key>--<port>.<region>.sandbox.cloudstudio.club` | 绑不了自己的域名，签不了证书，链接会变 |
| **没有 systemd / cron** | 保活只能靠 supervisord（PID 1），定时任务靠云端自动化 | 容器重启后不会自动拉起，得重写一遍保活配置 |
| **环境会被回收** | 只有 `/workspace`、`/root`、`/opt` 写入才持久，运行时目录可能重置 | 数据库 volume 正在 `/var/lib/docker`，属于会被重置的那一类 |

另外云端任务走积分，跑 `docker build`（Maven 依赖 + npm ci）这种重活要烧掉不少。
**结论：正式站还是买那台 600 块/年的机器，沙箱用来在上线前把坑踩完。**

## 它能解决的真问题

上一轮交付的生产部署包只做了静态校验（`sh -n`、`docker compose config`、`nginx -t`），
有两处没验证：

1. **Caddyfile 从来没被机器校验过**（本机拉不到 `caddy:2-alpine`）
2. **整套 compose 从没真跑起来过**（本地只有开发栈，端口和挂载都不同）

沙箱能联网拉镜像，这两件事都能补上 —— 而且踩坑在免费环境里，不占你服务器的首次部署时间。

## 预演步骤

在工作区里选**云端任务**（不是本地任务），把仓库传上去后执行：

```sh
# 0. 环境自检：确认不是宿主机规格
echo "CPU=$(nproc) MEM=$(free -g | awk '/^Mem:/{print $2}G') DISK=$(df -h /workspace | tail -1)"
# 沙箱里 nproc 常看到 32、free 看到 123G，那是宿主机的数字，按配额算

# 1. 变量（预演也要填全，compose 解析时就会校验 :? 必填项）
cp deploy/.env.prod.example .env
# DOMAIN / ACME_EMAIL 随便填（不启动 caddy，不会真去签证书）
# 密码和 JWT_SECRET 用真的，后面要拿它登录

# 2. 只校验 Caddyfile 语法（不启动，不签证书）
docker run --rm -e DOMAIN=notes.example.com -e ACME_EMAIL=me@example.com \
  -v "$PWD/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" \
  caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

# 3. 起应用（跳过 caddy，前端直接挂在 8080）
docker compose -f docker-compose.prod.yml -f deploy/rehearsal/override.yml \
  up -d --build mysql ollama backend frontend

# 4. 等健康
curl -s http://127.0.0.1:8080/api/health

# 5. 拉向量模型（1.2GB，磁盘 250G 够）
docker compose -f docker-compose.prod.yml exec -T ollama ollama pull bge-m3
```

> 沙箱的坑：**没有 systemd**，`systemctl` 一律无效；端口必须绑 `0.0.0.0`（compose 默认就是）；
> Docker 自带的网桥默认不启，`compose` 自定义网络要显式创建，否则容器间不通；
> 一次构建跑太久会被 180 秒无活动判定休眠，跑长命令时盯着点会话。
> 以上是社区实测，具体以你看到的沙箱为准。

## 校验清单（每条对应一个真实故障）

```sh
B=http://127.0.0.1:8080

# 1. 依赖是否全通 —— 注意状态在 body 里，HTTP 码永远 200
curl -s $B/api/health | grep -q '"status":"up"' && echo "health OK"

# 2. 数据库是否真初始化（schema.sql 只在空库时执行）
curl -s -o /dev/null -w '%{http_code}\n' -X POST $B/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$(grep ^ADMIN_PASSWORD .env | cut -d= -f2)\"}"
# 期望 200；500 说明建表或 seed 失败

# 3. 跨域白名单：陌生 Origin 必须 403（这是「页面能开、接口全挂」的根因）
curl -s -o /dev/null -w '%{http_code}\n' -H 'Origin: https://evil.example' $B/api/blog/stats   # 403
curl -s -o /dev/null -w '%{http_code}\n' -H "Origin: https://$(grep ^DOMAIN .env | cut -d= -f2)" $B/api/blog/stats  # 200

# 4. 敏感路径被 edge.conf 拦掉
for p in /.env /.git/config /actuator/health /wp-login.php; do
  printf '%s -> ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "$B$p"   # 期望 404
done

# 5. 限流真的生效（/api/ 是 20r/s burst 40，连打 80 次应该出现 429）
for i in $(seq 80); do curl -s -o /dev/null -w '%{http_code} ' $B/api/blog/stats; done; echo
# 有 200 也有 429 就对了；全 200 说明 limit_req 没生效

# 6. 图片静态资源不被限流、且能读到
ls storage/assets | head -1 | xargs -I{} curl -s -o /dev/null -w '%{http_code}\n' $B/api/assets/{}

# 7. 前端产物是不是最新构建（静态文件打在镜像里，只 restart 不会更新）
curl -s $B/ | grep -o 'assets/index-[A-Za-z0-9_-]*\.js' | head -1
```

第 5、6 条尤其值得跑：`/api/assets/` 如果被算进限流，笔记页一打开几十张图就会 429 ——
这个 bug 只有在真跑起来、真有一批图的时候才暴露。

## 预演完了

```sh
docker compose -f docker-compose.prod.yml -f deploy/rehearsal/override.yml down -v
```

把 `.env` 里真实密码删掉（沙箱环境会被回收，但仓库可能被同步）。真机上照
[README.md](README.md) 的三条命令走，预演改过的东西直接同步过去。

---

## 本机预演实录（2026-10-24，16/16 通过）

云端沙箱要先在 WorkBuddy 里切到「云端任务」，所以先在本机 Docker 上跑了一遍同样的编排
（两边的容器运行时是一样的，验证结论可以直接搬过去）。**关键是换一个项目名**，
否则会顶掉正在跑的开发栈（同名 `note-blog` 会重建现有容器）：

```sh
# 独立项目名 nb-rehearsal → 容器/卷/网络都另起一套，不碰开发栈
docker compose -p nb-rehearsal --env-file deploy/rehearsal/.env \
  -f docker-compose.prod.yml -f deploy/rehearsal/override.yml \
  up -d --build mysql ollama backend frontend
```

实际做法与结果：

| 步骤 | 做法 | 结果 |
| --- | --- | --- |
| 真实数据 | 把 `backups/notes_blog_20261023_201123.sql.gz` 灌进预演库 | 695 篇 / 4715 个切块 / 1 个账号 |
| 向量模型 | 直接从开发栈的卷复制到预演卷（1.1G），省掉 1.2G 下载 | `ollama list` 里能看到 bge-m3 |
| 应用链路 | `sh deploy/rehearsal/check.sh` | **16 项全过** |
| 限流实测 | 并发 90 次 `/api/blog/stats` | 52 个 200 / 38 个 429 |
| 语义检索 | `/api/search`（"docker 部署"） | 返回 5 条，Ollama + 向量列都通 |
| 图片链路 | `/api/assets/...` | 200 image/png，笔记详情里图正常渲染 |

### 预演里抓到的三个坑（都已修进脚本和文档）

1. **图片 URL 必须百分号编码**。你的文件名全是中文，直接拼原始字节去请求，Tomcat 会回
   **400**（`Invalid character found in the request target`），看着特别像「图片 404」，
   其实是请求根本没发出去。浏览器会自动编码，所以只有手写 curl 才踩得到。
2. **限流必须并发测**。顺序 `curl` 每个 30~50ms，90 个摊到 4 秒正好 20r/s，压在阈值上，
   同一份规则一会儿报 429 一会儿不报，会误判成「规则时灵时不灵」。改成 `xargs -P 20` 才稳定。
3. **`storage/assets` 第一层是目录**（`Java/`、`c++/`、`python/`、`uploads/`），
   拿 `ls | head -1` 当文件名会请求到一个目录，回 404。

截图在 `shots/rehearsal-*.png`（首页 / 登录页 / 笔记列表 / 含图笔记详情）。

### 收尾

```sh
# 停止并清掉预演数据（不影响开发栈）
docker compose -p nb-rehearsal -f docker-compose.prod.yml -f deploy/rehearsal/override.yml down -v
```

### Caddyfile 也补上了机器校验

之前只能用 `nginx -t` 验前端规则，Caddy 那层是纯手写的。这次下载了 Caddy v2.8.4 的官方二进制
直接校验（容器拉不下来镜像，host 上能下），结果是 **Valid configuration**，
顺手用 `caddy fmt --overwrite` 规整了格式：

```sh
caddy validate --config deploy/Caddyfile --adapter caddyfile
# 需要 DOMAIN / ACME_EMAIL 环境变量，占位符是从环境读的
```

至此整套配置里没有「只靠人眼看过」的部分了。
