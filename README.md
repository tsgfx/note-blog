# 一隅博客（note-blog）

把本地笔记系统重构成「**查 · 写 · 发布**」一体的私人博客。

- **查**：关键词 + 语义检索（bge-m3 向量），问问题直接定位到原文对应章节
- **写**：网页端 Markdown 编辑、标签管理
- **发布**：草稿一键发布，公开只读博客页

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router（样式手写，设计令牌在 `src/style.css`） |
| 后端 | Spring Boot 3.5（Java 21）+ Spring Security + Spring Data JPA |
| 数据库 | MySQL 9（`VECTOR(1024)` 原生向量列） |
| AI | 向量用 `bge-m3`（本地 Ollama 或硅基流动）；问答 LLM 走任意 OpenAI 兼容端点 |

> 前端**没有**用 Element Plus 之类的组件库：所有界面元素都是手写 CSS，颜色/字体/间距集中定义在设计令牌里（见「界面」一节）。

## 目录结构

```
note-blog/
├── backend/     Spring Boot 服务（REST API）
│   └── src/main/java/com/notesblog/
│       ├── controller/    REST 层
│       ├── service/       业务层
│       ├── repository/    数据访问层
│       ├── domain/        实体
│       ├── dto/           出参模型
│       ├── config/        Security / CORS / 配置绑定
│       └── common/        统一响应体、全局异常
├── frontend/    Vue 3 应用
│   └── src/
│       ├── api/           接口封装
│       ├── components/    全局组件（提示、确认框）
│       ├── lib/           主题、Markdown、剪贴板等工具
│       ├── router/        路由
│       └── views/         问答 / 笔记 / 博客 / 登录 / 审计 / 设置
├── db/          schema.sql（表结构）、provision.sql（建库建号）
├── deploy/      生产编排与主机规则：Caddyfile、nginx/edge.conf、
│                rules/（ufw / sshd / fail2ban）、apply.sh、up.sh、
│                pull-deploy.sh（服务器上发版）、verify.sh
├── docs/        部署与排错文档、路线图
├── scripts/     backup*.sh、export_markdown.py；一次性迁移脚本在 archive/
└── .env.example 本地配置模板（复制成 .env 后填值）
```

## 快速开始

### 1. 建库建账号（需 MySQL root，执行一次）

```bash
mysql -u root -p < db/provision.sql
mysql -u root -p < db/schema.sql
```

默认应用账号：`notes` / `notes_dev_pw`（生产请改，并同步改 `application.yml` 或环境变量 `DB_USER` / `DB_PASSWORD`）。

### 2. 启动后端（端口 8080）

```bash
cd backend
mvn spring-boot:run
# 验证：curl http://localhost:8080/api/health
```

### 3. 启动前端（端口 5173）

```bash
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173
```

前端开发服务器会把 `/api` 代理到 `http://localhost:8080`，无需处理跨域。

后端如果跑在容器里（映射到 8081），用封装好的脚本启动即可，不用改代码：

```bash
sh scripts/dev-frontend.sh      # 内部会设好 VITE_API_TARGET=http://localhost:8081
```

> **只绑 IPv6 的坑**：`vite.config.ts` 现在显式写了 `host: '127.0.0.1'`。
> 不写这一项时 Node 只绑 `[::1]`，会出现 `localhost:5173` 能开、`127.0.0.1:5173` 连不上的怪现象。

想让它常驻（关掉终端、重启电脑都还在），本机已备好 launchd 配置，在自己的终端里执行一次：

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.notesblog.vite-dev.plist
```

取消常驻：`launchctl bootout gui/$(id -u)/com.notesblog.vite-dev`

## 开发命令

```bash
# 后端测试：183 个用例，纯切片 + 单元测试，不需要 MySQL / Ollama / LLM
cd backend && mvn test

# 前端检查
cd frontend && npm run lint          # ESLint，0 warning 才通过
npm run format:check                 # Prettier 校验
npm run build                        # vue-tsc 类型检查 + 打包
```

测试守的是容易回归的行为：参数错误必须是 400 不是 500、无令牌必须 401、
改密后旧令牌必须失效、上传要校验魔数、路径穿越要被拦。

多数用例是纯切片 / 单元测试，不碰数据库；但 `ApplicationContextSmokeTest` 会起一个
真的 MySQL 容器（Testcontainers），否则「配置写错导致起不来」这类问题只能等上线才发现。
CI 因此需要 Docker —— GitHub 的 runner 自带，本地跑需要 Docker 可用，否则这 2 个用例会被跳过。

> **本机用 colima 时会被静默跳过**：Testcontainers 只探 `/var/run/docker.sock`，
> colima 的 socket 在 `~/.colima/default/docker.sock`。表现是本地显示 `Skipped: 2`，
> 看着是绿的，其实没跑过——CI 上一次都没绿时，本地一直是这个样子。

## 容器化部署

不想装 Java / Node / MySQL，可以直接用容器跑。四个服务（mysql + ollama + backend + frontend）编在一起：

```bash
cp .env.example .env                             # 填 JWT_SECRET、ADMIN_PASSWORD、LLM_API_KEY
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3    # 首次需拉 1.2GB 模型
```

打开 http://localhost:8088 即可。数据存于 named volume，图片目录仍是 `./storage/assets`（复用宿主机已有的图）。

- 部署与排错：[docs/docker-deploy.md](docs/docker-deploy.md)
- Docker 本体安装（macOS CLI）：[docs/docker-install-macos.md](docs/docker-install-macos.md)

### 云端生产部署

上面的 `docker-compose.yml` 是本地开发用的（会 publish 8081/8088）。
放到公网服务器请用生产编排，它只有 Caddy 一个入口，防火墙/限流/黑名单规则全在仓库里：

```bash
cp deploy/.env.prod.example .env
sudo sh deploy/apply.sh    # ufw + SSH 加固 + fail2ban
sh deploy/up.sh            # 构建启动 → 等健康 → 自检
```

上线后日常发版在服务器上执行，Mac 不需要开机：

```bash
ssh root@你的服务器
cd /opt/note-blog && sh deploy/pull-deploy.sh
```

选型、规则改动对照表、已知坑见 [deploy/README.md](deploy/README.md)。

## 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| **M0** | 骨架跑通：Spring Boot + Vue + MySQL 建库建表 | ✅ |
| **M1** | 查：导入 695 篇笔记 + 4715 条向量；语义检索；原文定位 | ✅ |
| **M2** | 写：笔记 CRUD、Markdown 编辑（实时预览）、标签 | ✅ |
| **M3** | 发布：草稿→发布、slug、JWT 登录、公开博客页、自动向量重建 | ✅ |
| **M4** | 问答：RAG 流式问答（Spring AI 风格 SSE + bge-m3 + OpenAI 兼容 LLM） | ✅ |
| **M4.1** | 图片：迁移 2236 张旧图到 `storage/assets`，改写 311 篇笔记链接，编辑器支持上传 | ✅ |
| **M5** | 打磨：RSS、导出 Markdown 回 Obsidian、备份、操作日志 | ✅ |
| **M6** | 多级目录：左侧目录树（可折叠、带篇数）、按任意层级筛选、`?path=` 可分享 | ✅ |
| **M7** | 上云与运维：ECS 生产部署、每日备份（含异地副本）、ufw + fail2ban 主机加固 | ✅ |

## 目录导航（M6）

左侧从「单级库」换成可折叠的多级树：`sourcePath` 逐段展开，父目录显示整棵子树的篇数，
点任意层级都按前缀筛选（选 `c++/C语言基础` = 76 篇，选 `c++` = 217 篇）。

几个有意为之的细节：

- **树上数字跟着「全部/已发布/草稿」变**——树上写 12 篇、点进去 3 篇，比没有导航更让人不信任。
- **选中项写进 URL**（`/notes?path=c%2B%2B%2FC...`），刷新、分享、从详情返回都回到同一位置。
- **列表副标题显示相对路径**：选了 `c++/C语言基础` 之后，行里只显示 `第二章_...` 或「本目录」，
  不再每行顶着一整条路径。
- 目录与标签互斥清理：切目录会清掉上一层选的标签，否则组合筛出空结果。

## 当前数据状态

库里现有 **680 篇笔记**（旧库导入 695 篇，后续合并去重），其中 **已发布 4 篇**，其余仍是草稿（DRAFT）。
公开的 `/blog` 因此只展示这 4 篇 —— 其余需要在后台逐篇或批量发布，发布前会走预检（空正文阻断，密钥/手机号等告警）。

`SITE_BASE_URL` 已指向线上地址；本地开发仍是 `http://localhost:5173`，改它会影响 RSS 与 sitemap 里的链接。

## 界面

- 深色模式：顶部「深色 / 浅色」切换，选择会保存在浏览器；首次访问跟随系统设置。
- 颜色、字体、间距全部定义在 `frontend/src/style.css` 顶部的令牌中，深色令牌在其后的 `:root[data-theme='dark']`。

## 运维

```bash
# 备份数据库（mysqldump）和图片目录；默认保留最近 14 份
DB_PASSWORD='...' scripts/backup.sh

# 服务器上每日备份 + 加密上传 COS（cron 调用，见 deploy/README.md）
COMPOSE_FILE=docker-compose.prod.yml sh scripts/backup-cloud.sh

# 导出为 Obsidian 可直接打开的 Markdown（只读数据库，默认输出到 export/）
DB_PASSWORD='...' python3 scripts/export_markdown.py
```

- RSS 订阅：`GET /api/blog/rss.xml`（只包含已发布文章）。对外地址由 `SITE_BASE_URL` 环境变量决定。
- 操作日志页面：登录后顶部「操作日志」，或 `/admin/audit`。接口 `GET /api/admin/audit-logs`（仅管理员）。记录登录（含失败，不记录密码）、笔记增删改、发布/下线、图片上传。
- 备份脚本使用 `--skip-lock-tables` 而非 `--single-transaction`：后者在当前应用账号权限下会失败。个人博客写入频率低，备份不是严格的一致性快照，这个取舍可以接受。
- 异地备份：服务器每日备份成功后，`backup-upload-cos.sh` 把 dump 用 AES-256 加密后传到 COS 的 `backups/` 前缀；密钥在服务器 `/opt/note-blog/.backup-key`。恢复命令见 `deploy/README.md`。
- 可用性探活：仓库里的 `.github/workflows/uptime.yml` 每 10 分钟探 `GET /api/health` 与首页，
  失败即让 job 变红、由 GitHub 发通知。判据是「HTTP 200 **且** body 含 `"status":"up"`」——
  这个接口依赖挂掉时仍回 200，只看状态码等于没监控。站点地址取自仓库变量 `SITE_BASE_URL`。

> 图片迁移：`python3 scripts/archive/import_assets.py --apply`（默认预览）。原语雀导出中有 12 处图片引用在 vault 里本就不存在（集中在 Git 教程、Redis 教程、数学建模等笔记），保留原链接，页面显示为破损图标。

> 编辑或新建笔记后会异步切块并重建向量；重建期间该笔记短暂不会出现在语义搜索结果中。

## 配置说明

`backend/src/main/resources/application.yml`：

```yaml
notes:
  embedding:
    # ollama（默认）：本地 Ollama，api-key 留空
    # openai：任意 OpenAI 兼容服务（如硅基流动），model 写完整 id，api-key 必填
    backend: ${EMBEDDING_BACKEND:ollama}
    base-url: ${EMBEDDING_BASE_URL:http://localhost:11434}
    model: ${EMBEDDING_MODEL:bge-m3}
    api-key: ${EMBEDDING_API_KEY:}
  llm:
    base-url: ${LLM_BASE_URL:https://opencode.ai/zen/go/v1}
    model: ${LLM_MODEL:minimax-m3}
    api-key: ${LLM_API_KEY:}   # 用环境变量注入，别写进仓库
```

> 向量模型必须与建库时一致（`bge-m3`，1024 维），换模型等于换向量空间，检索会全废。
> 本地 Ollama 与硅基流动的 `bge-m3` 向量互通，可以在两者之间切换而不用重建索引。

项目的 `.env` 保存全部本地配置（密钥、管理员密码、JWT 密钥、模型与向量后端地址）。
`.env` 已加入 `.gitignore`，不会被提交；首次使用从模板复制：

```bash
cp .env.example .env
```

启动服务：

```bash
./run.sh            # 同时启动后端(8080)与前端(5173)
./run.sh backend    # 只启动后端
./run.sh frontend   # 只启动前端
```

不要把密钥写进 `application.yml` 或提交到仓库。首次使用请修改 `.env` 里的
`ADMIN_PASSWORD` 与 `JWT_SECRET`。

> 注意：`run.sh` 会执行 `set -a; . ./.env`，**`.env` 里的值会覆盖你 export 的同名变量**。
> 所以用 `./run.sh` 启动时，改配置只能改 `.env`，命令行 `export` 不生效。
> 直接用 `mvn spring-boot:run` 启动时，环境变量才会生效。

**登录密码怎么改**：`ADMIN_PASSWORD` 只在首次建库时用来创建管理员。库里已有该账号后，
改 `.env` 不会覆盖密码——请登录后点右上角「设置」→「账号设置」修改。
改密会把令牌版本号 +1，**所有已登录设备立即失效**，需要用新密码重新登录。

开发环境首次建库时会按 `.env` 里的 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建管理员。
**这个密码只在建库时生效**，库里已有账号后改 `.env` 不会覆盖密码——
请登录后点右上角「设置」修改。改密会把令牌版本号 +1，所有已登录设备立即失效。

`/api/blog/**`、`/api/health`、`/api/auth/login`、`/api/ask`（游客限次，见下）、读取图片公开，
其余后台、搜索、问答接口需要 JWT。

> **游客的两个能力**：问答（每天有限次）与评论（默认可见，管理员可隐藏/删除）。
> 游客的检索范围**只有已发布文章**——分片索引里也有草稿，`AskController` 与
> `PublicSearchController` 走 `searchPublished` 过滤。这条是安全边界，有测试守着
> （`SemanticSearchScopeTest`）。

## 已接入 API

除标注「公开」的，其余都需要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（公开） |
| POST | `/api/auth/login` | 登录，返回 JWT（公开） |
| POST | `/api/account/password` | 修改密码，成功后所有旧令牌立即失效 |
| GET | `/api/notes` | 笔记分页列表，可按 `status`、`path`（目录前缀，多级）过滤 |
| GET | `/api/notes/folders` | 多级目录树，每层带篇数（跟随 `status` 变化） |
| GET | `/api/notes/libraries` | 只有第一层的库名与篇数（旧接口，保留兼容） |
| GET | `/api/notes/tags` | 标签与篇数，可传 `path` 只统计该目录 |
| GET | `/api/notes/{slug}` | 笔记详情 |
| GET | `/api/notes/{id}/related` | 相关笔记（分片向量均值比对，不调 embedding） |
| POST | `/api/notes` | 新建笔记（草稿） |
| PATCH | `/api/notes/{id}` | 更新笔记，改正文会触发向量重建 |
| DELETE | `/api/notes/{id}` | 删除笔记（同时清掉该笔记的分片） |
| POST | `/api/notes/{id}/publish` | 发布 |
| POST | `/api/notes/{id}/unpublish` | 下线回草稿 |
| POST | `/api/notes/batch/publish` | 批量发布（≤200 篇，返回逐条跳过原因） |
| POST | `/api/notes/batch/unpublish` | 批量下线（≤200 篇） |
| POST | `/api/notes/preflight` | 批量发布前预检（空正文阻断 / 密钥、手机号告警） |
| POST | `/api/notes/{id}/generate-meta` | 自动生成摘要与标签（`force=true` 全量重写） |
| POST | `/api/notes/generate-meta/batch` | 批量补齐摘要与标签 |
| POST | `/api/notes/reindex` | 重建向量索引（返回剩余待处理数） |
| POST | `/api/search` | 混合检索，返回命中的分片（登录=全库，匿名=仅已发布） |
| POST | `/api/ask` | RAG 问答，SSE 流式（**公开**；游客按 IP 每日限次，管理员不限） |
| GET | `/api/assets/**` | 图片读取（公开；匿名只放行已发布文章引用的图） |
| POST | `/api/assets` | 上传图片（multipart，仅 png/jpg/gif/webp，≤10MB） |
| GET | `/api/blog/posts` | 已发布文章列表（公开） |
| GET | `/api/blog/posts/{slug}` | 已发布文章详情（公开） |
| POST | `/api/blog/search` | 游客语义搜索，**只返回已发布文章**（公开） |
| GET | `/api/blog/posts/{slug}/comments` | 某篇文章的公开评论（公开，只含可见的） |
| POST | `/api/blog/posts/{slug}/comments` | 游客发评论（公开；蜜罐 + 每分钟 1 条 + 每天 10 条） |
| GET | `/api/blog/rss.xml` | RSS 2.0（公开） |
| GET | `/api/blog/sitemap.xml` | sitemap（公开） |
| GET | `/api/blog/robots.txt` | robots.txt（公开） |
| GET | `/api/admin/audit-logs` | 操作日志（仅管理员） |
| GET | `/api/admin/comments` | 全部评论，含已隐藏与来源 IP（仅管理员） |
| PATCH | `/api/admin/comments/{id}/visibility` | 隐藏 / 恢复可见（软隐藏，可撤销） |
| DELETE | `/api/admin/comments/{id}` | 永久删除评论 |
| GET | `/api/system/backup` | 备份状态：最近成功时间、体积、份数（需登录） |

> `/api/auth/**` 是 `permitAll`，只放匿名入口（登录）。任何需要身份的写操作
> 必须挂在 `/api/account/**` 下——这条约束写在 `SecurityConfig` 的类注释里，也有测试守着。

### 游客问答的每日额度

两道并行，缺一不可：

| 那道 | 位置 | 上限 | 特点 |
|---|---|---|---|
| 浏览器 | `frontend/src/lib/guestQuota.ts` | 5 条/天 | 显示剩余并本地拦截；清缓存即绕过，**只负责体验** |
| 按 IP | `GuestQuotaService`（落库 `guest_daily_usage`） | 15 条/天 | 真正的硬上限；重启、发版都不清零 |

IP 上限刻意大于承诺的 5 条：手机流量和不少宽带是共享出口 IP，严格按 5 条会让
同一出口下的人互相挤占。前端已经把单浏览器压到 5 条了。

配置在 `.env` 的 `GUEST_ASK_DAILY_ALLOWANCE` / `GUEST_ASK_IP_DAILY_CEILING`。
**`RATE_LIMIT_ASK` 必须 ≥ `GUEST_ASK_IP_DAILY_CEILING`**，否则游客会先撞上
「请求过于频繁，请 60 秒后再试」而不是「今天的提问次数已用完」——语义不对，
本地端到端实测踩过这个坑。


两个核心接口的报文：

```text
POST /api/search
Body: { "question": "GFS_VL 的环境怎么配置？", "limit": 8 }
返回：语义命中的笔记、章节、片段和 RRF 融合分

POST /api/ask
Body: { "question": "GFS_VL 的环境怎么配置？" }
返回：SSE；事件顺序为 sources → quota（仅游客）→ delta* → done/error
      游客配额用完时不开流，直接回 429 + Retry-After，body 里带 resetInSeconds
```

所有接口返回统一响应体 `{ code, message, data }`，`code=0` 表示成功。
参数不合法（畸形 JSON、枚举取值错误、缺参数）返回 400 而不是 500。

### 检索原理（混合检索）

问答和搜索使用两路召回再融合：

- **语义召回**：问题经 bge-m3 转为向量，与全部分片做余弦相似度排序（分片与向量常驻内存，文档修改后自动失效重载）。
- **关键词召回**：英文/标识符整词（如 `sm_120`、`MAX_JOBS`）精确子串匹配，中文按二元组匹配并降低权重。
- **融合**：RRF（倒数排名融合），关键词路权重 1.5，使精确标识符在排名平手时优先。
- **去重**：同一篇笔记最多返回 2 个分片，避免来源全部集中在一篇文章。

语义向量对标识符（版本号、环境变量、命令参数）匹配不稳定，因此必须有关键词路。

MySQL Community 9.5 当前支持 `VECTOR(1024)` 存储，但本机没有
`VECTOR_DISTANCE()` 函数，因此搜索采用「MySQL 读取向量 + Java JVM 计算余弦距离」。
当前 4715 个分片约 19MB，适合当前笔记体量；未来数据量显著增长时再切换专用向量库或支持向量距离的 MySQL 发行版。

## 与旧系统（~/note-ai）的关系

迁移已完成：`vault/` 的 695 篇 Markdown 与 2236 张图片、`index/` 的 4715 条向量
都已导入本项目（MySQL `notes_blog` 与 `storage/assets`，后续合并去重至 680 篇），旧目录可安全删除。

`scripts/archive/` 下的 `import_legacy.py`、`import_assets.py`、`fix_titles.py` 是**一次性迁移脚本**（已归档），
它们读取旧目录，仅在需要重新导入时使用；日常运行不依赖旧目录。
