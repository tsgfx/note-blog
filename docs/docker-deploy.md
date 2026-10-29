# 容器化部署

四个服务编在一起：`mysql` + `ollama` + `backend` + `frontend`。
前端 Nginx 同时托管静态页并把 `/api` 反代到后端，所以对外只需要一个端口。

## 快速开始

```bash
# 前提：Docker 已装好（见 docs/docker-install-macos.md）
cp .env.example .env      # 首次：填 JWT_SECRET、ADMIN_PASSWORD、LLM_API_KEY
docker compose up -d --build
```

| 入口 | 地址 | 说明 |
|---|---|---|
| 博客前台 | http://localhost:8088 | Nginx，静态页 + `/api` 反代 |
| 后端直连 | http://localhost:8081 | 调试用，正常不对外暴露 |
| MySQL | 仅容器网络内 | 不映射端口，避免误暴露 |
| Ollama | 仅容器网络内 | 后端用 `http://ollama:11434` 访问 |

首次启动会自动完成：

1. MySQL 执行 `db/schema.sql` 建库建表（**只在数据目录为空时执行一次**）
2. 后端按 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建管理员
3. 拉取 `bge-m3` 模型需要单独执行一次（见下）

## 一次性：准备向量模型

```bash
docker compose exec ollama ollama pull bge-m3     # 约 1.2GB
```

不拉这个，搜索和问答会报连接错误（后端本身能正常启动）。

## 数据

| 内容 | 存放位置 |
|---|---|
| 笔记与向量 | named volume `note-blog_mysql-data` |
| 模型文件 | named volume `note-blog_ollama-models` |
| 图片 | 宿主机 `./storage/assets`（bind mount，原有图片直接复用） |

**从本地库迁移数据**的做法（本项目实际用过）：

```bash
# 1. 只导数据，不含建表语句（表结构由 schema.sql 负责）
mysqldump -u notes -p --single-transaction --no-create-info --complete-insert \
  --skip-add-locks --skip-disable-keys --set-gtid-purged=OFF notes_blog \
  users tags notes note_tags note_chunks audit_logs > /tmp/nb-data.sql

# 2. 灌进容器（关外键检查，避免导入顺序问题）
( echo "SET FOREIGN_KEY_CHECKS=0;"; cat /tmp/nb-data.sql; echo "SET FOREIGN_KEY_CHECKS=1;" ) \
  | docker compose exec -T mysql mysql --default-character-set=utf8mb4 -u notes -p notes_blog
```

`note_chunks.embedding` 是 `VECTOR(1024)`，mysqldump 会导出成二进制字面量，直接导入即可，不需要重新算向量。

## 常用命令

```bash
docker compose ps                      # 状态
docker compose logs -f backend         # 后端日志
docker compose restart backend         # 只重启后端
docker compose down                    # 停止，数据保留
docker compose down -v                 # 停止并删除数据卷（不可恢复）
docker compose exec mysql mysql -u notes -p notes_blog    # 进数据库
```

## 改代码后怎么生效

静态文件是**构建进镜像**的（`COPY --from=builder /app/dist`），不是挂载本地目录。
所以只改 `src/`、只跑本地 `npm run build`、只重启容器，都不会改变 8088 的页面。

一条命令搞定，它会按 `git status` 的改动自动判断要重建谁，并做健康检查：

```bash
sh scripts/deploy.sh            # 自动：改了 frontend/ 就重建前端，改了 backend/ 就重建后端
sh scripts/deploy.sh frontend   # 强制只重建前端
sh scripts/deploy.sh backend    # 强制只重建后端
sh scripts/deploy.sh all        # 两个都重建
```

不想用脚本时，等价的手工命令：

| 改动 | 命令 | 生效位置 |
|---|---|---|
| 后端 Java / `application.yml` | `docker compose up -d --build backend` | `/api/**` |
| 前端 Vue / `nginx.conf` | `docker compose up -d --build frontend` | 8088 的页面 |
| 两者都改 | 上两条各跑一次 | 页面 + 接口 |
| `db/schema.sql` | `docker compose down -v && docker compose up -d`（**会清空数据，先备份**） | 表结构 |

### 怎么确认"真的生效了"

不要靠"看起来一样"判断，对比入口 JS 的文件名（它带内容 hash）：

```bash
# 8088 实际在提供哪个版本
curl -s http://localhost:8088/ | grep -o 'assets/index-[A-Za-z0-9_-]*\.js'
# 本机构建产物是哪个版本
ls frontend/dist/assets/index-*.js
```

- **两边不同** → 镜像还是旧的，rebuild 没跑或跑错了服务
- **两边相同** → 镜像已是新的，如果页面还不对，那是浏览器缓存，刷新即可

`index.html` 已设 `Cache-Control: no-cache`（配合 ETag 走 304），所以换版本后**普通刷新**就能拿到新的入口；
带 hash 的 `/assets/` 仍是 7 天长缓存。在这之前只有 ETag/Last-Modified，浏览器可能继续用旧 HTML，
必须 `Cmd+Shift+R`。

## 踩过的坑

**1. 容器解析不了外网域名**
宿主机如果用了 Tailscale（DNS 为 `100.100.100.100`），虚拟机里的容器继承不到，`ollama pull`
会报 `lookup registry.ollama.ai ... server misbehaving`。解决办法是给需要外网的容器显式指定 DNS，
compose 里已经加上：

```yaml
    dns:
      - 223.5.5.5
      - 119.29.29.29
```

**2. Docker Hub 拉不动**
`docker compose up` 卡在 pull 时，检查 `docker info | grep -A3 "Registry Mirrors"`，
没配就按 `docs/docker-install-macos.md` 第 ④ 节配置。

**3. SSE 变成"等半天一次性吐出"**
Nginx 默认缓冲上游响应，`/api/ask` 的流式会被攒着。`frontend/nginx.conf` 里对 `= /api/ask`
单独关了 `proxy_buffering`。改配置后要 `--build frontend` 才生效。

**4. 图片上传 413**
Nginx 默认 `client_max_body_size 1m`，后端允许 10MB。已在配置里放宽到 12m。

**5. `schema.sql` 改了没生效**
`docker-entrypoint-initdb.d` 只在数据目录为空时执行。要么清卷，要么手工执行 SQL。

**6. 页面能打开，但登录报「请求失败（403）」**
CORS 白名单里没有当前访问地址。浏览器的真实行为是：**即使前后端同源，POST 也会带 `Origin` 头**，
Spring 的 CORS 过滤器看到 Origin 就比对白名单，不在名单里直接返回 403（`Invalid CORS request`）。

默认白名单是 **本机任意端口**（`http://localhost:[*]`、`http://127.0.0.1:[*]`），
所以 dev 5173、预览 4173、容器 8088 都不需要再改配置。

**手机 / 局域网访问要显式加地址**（这是第四次因为漏登记吃 403 了）：

```bash
# .env
CORS_ALLOWED_ORIGINS=http://localhost:[*],http://127.0.0.1:[*],http://192.168.1.7:8088
```

```bash
docker compose up -d --build backend    # 改完必须重建 backend，环境变量才会进容器
```

> ⚠️ **IP 部分不要用通配**。Spring 把 `192.168.*` 展开成正则 `192\.168\..*`，
> 于是 `http://192.168.evil.example` 也会被放行——测试里专门有一条守着这个边界。
> 端口可以通配（`:[*]`），主机名/IP 必须写具体值。

换网络后 IP 变了要同步改 `.env`。查当前 IP：`ipconfig getifaddr en0`。
用 Tailscale 从外网访问时同理，要把 `100.x.y.z` 那条也加进白名单。

上线到真实域名时必须用环境变量覆盖，并把你自己的域名写进去：

```bash
CORS_ALLOWED_ORIGINS=https://notes.example.com docker compose up -d
```

排查时注意 **curl 默认不发 Origin 头**，所以不带 `-H "Origin: ..."` 的验证会假通过：

```bash
curl -i -X POST http://localhost:8088/api/auth/login \
  -H "Origin: http://localhost:8088" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<你的密码>"}'
```

## 生产环境要改的四处

1. `.env` 里的 `JWT_SECRET` 必须是自己生成的值；`ADMIN_PASSWORD` 首次建库后改不动，
   请登录后走「设置」页面修改（改密会吊销所有已登录设备）
2. `SITE_BASE_URL_CONTAINER` 改成真实域名，否则 RSS 里的链接指向 `localhost`
3. `CORS_ALLOWED_ORIGINS` 改成真实域名，否则浏览器里所有接口 403
4. 把 `docker compose up` 里的 MySQL 端口保持不映射；定期跑 `scripts/backup.sh`

## 前端冒烟测试（Playwright）

```bash
docker compose up -d                     # 先把栈跑起来
cd frontend
E2E_ADMIN_PASSWORD=xxx npm run e2e       # 5 个用例，约 5 秒
```

**刻意不配 `webServer`**：用例跑在真实的 nginx + 后端 + MySQL 上，不是 `vite preview`
一个只有前端的空壳。原因很直接——2026-10-23 出现过「页面能开、接口全挂」
（静态服务器下 GET 404 / POST 502），那种状态下前端测试全绿而系统是坏的。

覆盖的 5 条：

| 用例 | 守的是什么 |
|---|---|
| 未登录访问 `/notes` | 被送到登录页，且 `redirect` 参数没丢 |
| `/blog` 公开页 | 不需要登录也能出内容 |
| 登录 → 笔记列表 | 骨架必须在合理时间内消失，不能永远停在加载态 |
| 打开一篇笔记 | 正文真的渲染出来了（不是空白页） |
| `/settings` | 依赖健康区显示出来 |

**最重要的一条断言不在表里**：每个用例都挂了一个监听器，`/api/**` 的响应
**不许出现 4xx/5xx**（401 除外，那是预期）。页面「看起来正常」不算通过。

账号密码只从环境变量读，不落盘、不进 git。

**有效性验证方式**：`docker compose stop backend` 后重跑，用例应变红并留下
trace 与截图（`frontend/test-results/`）；启动后恢复绿。

> CI 现状：暂未接入。要用真栈就得在 runner 里拉起 mysql + backend + frontend，
> 大约多花 4–5 分钟，而 GitHub 免费额度下这只是「锦上添花」。
> 接的时候注意 backend 的 `depends_on: ollama healthy`，需要 `--no-deps` 跳过它
> （问答才需要 Ollama，冒烟测试不需要）。

## 备份与恢复

```bash
scripts/backup.sh          # 容器在跑时不需要任何密码
```

脚本会**自动判断数据在哪一边**：容器里的 mysql 在跑就备份容器（`docker compose exec`），
否则退回本机 `mysqldump`。用 `BACKUP_FROM=docker|local` 可以强制指定。

> 容器模式下用的是**容器自带的 `MYSQL_USER` / `MYSQL_PASSWORD`**，
> 宿主机不需要知道数据库密码——定时任务因此不必把密码写进 plist 或 `.env`。
> 只有 `BACKUP_FROM=local` 才需要 `DB_PASSWORD`。

> ⚠️ 那条「容器还是本机」的判断不是多余的。数据迁到容器之后，如果脚本还在连 `127.0.0.1`，
> 备出来的会是**迁移前的旧库**，看着成功其实全错。这个问题真发生过。

备份产物：`backups/notes_blog_<时间戳>.sql.gz`（数据，约 22MB）+ `backups/storage_<时间戳>.tar.gz`（图片，约 240MB），
默认各保留 14 份（`BACKUP_KEEP` 可调）——**14 份约 3.6GB，注意磁盘**。

### 每天自动备份（launchd）

```bash
cp scripts/com.notesblog.backup.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.notesblog.backup.plist
launchctl list | grep notesblog          # 第二列是上次退出码，0 才算正常
```

> `launchctl bootstrap` **必须在自己的终端里执行**。在自动化会话/沙箱里跑会报
> `Bootstrap failed: 5: Input/output error`，这不是配置写错了。

- 每天 03:00 执行 `scripts/scheduled-backup.sh`，日志 `~/Library/Logs/notesblog-backup.log`
- 机器在睡眠时，唤醒后 launchd 会补跑一次
- 立即试跑：`launchctl kickstart -k gui/$(id -u)/com.notesblog.backup`

### 备份失败要看得见

**备份真正的风险不是"备不出来"，而是三个月后才发现从没成功过。**

- 成功：删掉标记文件，`backups/` 里最新一份的时间就是"最近成功备份"
- 失败：`scheduled-backup.sh` 写入 `backups/.last-backup-error`（含时间与日志路径）
- 设置页的「备份」区读 `GET /api/system/backup`：超过 **48 小时**没成功就亮黄条，
  有失败标记就显示原文

后端通过 `./backups:/data/backups:ro` **只读**挂载这个目录——它只读文件时间与大小，
不解析备份内容，也不写这个目录（备份是宿主机的事）。

### 定期做一次恢复验证

**备份只有能恢复才算备份。** 验证是非破坏性的——恢复到临时库比对后删掉：

```bash
DUMP=$(ls -t backups/notes_blog_*.sql.gz | head -1)
docker compose exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" \
  -e "DROP DATABASE IF EXISTS notes_blog_check; CREATE DATABASE notes_blog_check DEFAULT CHARSET utf8mb4;"

LC_ALL=C gunzip -c "$DUMP" | LC_ALL=C sed 's/notes_blog/notes_blog_check/g' \
  | docker compose exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4

docker compose exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -e "
SELECT 'notes', COUNT(*) FROM notes_blog_check.notes
UNION ALL SELECT 'note_chunks', COUNT(*) FROM notes_blog_check.note_chunks
UNION ALL SELECT 'tags', COUNT(*) FROM notes_blog_check.tags
UNION ALL SELECT '向量正确', COUNT(*) FROM notes_blog_check.note_chunks WHERE VECTOR_DIM(embedding)=1024;"

docker compose exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE notes_blog_check;"
```

两个容易踩的点：

- `sed` 必须加 `LC_ALL=C`。备份里有中文和二进制向量，BSD sed 遇到非法字节序列会直接
  `illegal byte sequence` 中断，管道断掉，恢复进去是空库且报错容易被忽略。
- 向量列单独验一下：`VECTOR_DIM(embedding)=1024` 的行数应当等于分片总数。
  本次实测是 **695 篇正文哈希全一致、4715 条分片向量维度全正确**。

## Ollama 想换回宿主机

`:8088` 那套不动，只改两处：

1. `docker-compose.yml` 里 backend 的 `EMBEDDING_BASE_URL` 改成 `http://host.docker.internal:11434`
2. 删掉 `depends_on` 里的 ollama 条件，或直接不启动 ollama 服务

好处是 macOS 上能用 Metal GPU 跑 `bge-m3`，向量化快一个数量级。代价是部署到 Linux 服务器时不能一条命令全栈起来。
