# macOS 命令行装 Docker（不装 Docker Desktop）

只用 Homebrew + colima，全程终端操作，没有图形界面。

- **colima**：在 macOS 上用轻量虚拟机跑容器运行时，替代 Docker Desktop 的后台引擎。
- **docker**：CLI 客户端（只有命令，没有引擎）。
- **docker-compose**：Compose v2 插件，装好后用 `docker compose`（不是老的 `docker-compose`）。

已验证环境：macOS (Apple Silicon) + Homebrew 7.x，可用磁盘 92GB。全部装完约占用 1.5–2GB（含虚拟机镜像）。

---

## 1. 安装

> **国内网络先看这行**：`brew install` 若卡在 `==> Auto-updating Homebrew...`，
> 是它在 `git fetch` Homebrew 自己的仓库。`Ctrl+C` 中断后改用带镜像环境变量的一次性命令：
>
> ```bash
> HOMEBREW_NO_AUTO_UPDATE=1 \
> HOMEBREW_API_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles/api \
> HOMEBREW_BOTTLE_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles \
> brew install colima docker docker-compose
> ```
>
> 只对这一次生效，不改任何配置文件。

```bash
brew install colima docker docker-compose
```

装完先确认版本：

```bash
colima version
docker --version
```

此时 `docker` 还连不上任何引擎，`docker version` 会报 `Cannot connect to the Docker daemon` —— 正常，下一步才起引擎。

## 2. 把 compose 注册为 docker 插件

Homebrew 装的 `docker-compose` 是个**插件**，不在 PATH 上，要让 `docker compose` 找到它。官方推荐的做法是写进 docker 的配置文件：

```bash
mkdir -p ~/.docker
cat > ~/.docker/config.json <<'EOF'
{
  "cliPluginsExtraDirs": [
    "/opt/homebrew/lib/docker/cli-plugins"
  ]
}
EOF
```

> Apple Silicon 的 Homebrew 前缀是 `/opt/homebrew`；Intel Mac 是 `/usr/local`。
> 用 `brew --prefix` 确认，路径要按实际前缀改。
>
> 本机当前 `~/.docker/config.json` **不存在**，上面的命令可以直接执行，不会覆盖任何东西。

验证：

```bash
docker compose version
# 期望：Docker Compose version v2.x 或更高
```

如果这条没输出但 `brew list docker-compose` 显示已装，改用手动链一次（等价方案）：

```bash
mkdir -p ~/.docker/cli-plugins
ln -sfn "$(brew --prefix)/opt/docker-compose/bin/docker-compose" ~/.docker/cli-plugins/docker-compose
```

⚠️ 配置 `config.json` 时**不要整个覆盖**已有文件——如果这个文件已经存在，用编辑器加 `cliPluginsExtraDirs` 字段，别把里面原有的 auths 冲掉（除非你确认里面没内容）。

## 3. 启动虚拟机（引擎）

```bash
colima start --cpu 4 --memory 8 --disk 60
```

- `--memory 8` 是给**虚拟机**的内存。本项目要跑 MySQL + Spring Boot + Ollama，建议不低于 8GB；机器只有 16GB 内存的话用 `--memory 6`。
- `--disk 60` 是虚拟机磁盘。镜像（尤其 `maven` 构建层和 Ollama 的 bge-m3 模型）会占 5–8GB，别给太小。
- 首次启动要下载虚拟机镜像，几分钟属正常。

启动完成后：

```bash
docker version
# 应该同时看到 Client 和 Server 两段，Server 段里有 Engine 版本
docker info | grep -E "Server Version|Operating System|Total Memory"
```

跑个最简容器验证：

```bash
docker run --rm hello-world
```

> 如果 `docker version` 里 Server 段还是连不上，先看 `colima status`。
> 如果之前装过 Docker Desktop，可能需要 `docker context use colima` 切上下文。

## 4. 常用管理命令

```bash
colima status          # 看虚拟机状态
colima stop            # 停止（省内存，容器会停）
colima start            # 再次启动，容器按 restart 策略自恢复
colima list            # 所有实例
```

开机自启（可选）：

```bash
brew services start colima
```

---

## 5. 镜像拉不动时（国内网络）

`docker pull` 慢或超时，是因为 Docker Hub 在国内基本不可直连。**2024 年之后大批公共加速器已失效**，网上搜到的多数地址是死的。

按这个顺序试：

1. 先直接试官方源，有时能通：
   ```bash
   docker pull mysql:9
   ```
2. 配镜像加速（写进 colima 的 daemon 配置，改完要重启虚拟机）：
   ```bash
   colima stop
   colima start --edit          # 打开配置，找到 docker: 段，加 registry-mirrors
   ```
   ```yaml
   docker:
     registry-mirrors:
       - https://docker.m.daocloud.io
       - https://docker.1ms.run
   ```
3. 仍然不行就用代理拉，或改用云厂商内网镜像（腾讯云 `mirror.ccs.tencentyun.com`、阿里云容器镜像服务的专属加速地址）。

验证加速是否生效：

```bash
docker info | grep -A5 "Registry Mirrors"
```

> 拉取失败时先看报错里的域名：`dial tcp ... i/o timeout` 是网络不通，`manifest unknown` 是**标签写错了**。
> 后者尤其常见——比如 `mysql:9.5` 这个标签并不存在，官方给的是 `9`、`9.7`、`lts`、`8.4` 这类。

---

## 本机实际安装记录（2026-10-22）

这套命令是实际跑通的那一版，含国内网络下的三处绕行。

### ① 用镜像变量装 brew 包

直连会卡在 `==> Auto-updating Homebrew...`（它在 `git fetch` 自己的仓库）。

```bash
HOMEBREW_NO_AUTO_UPDATE=1 \
HOMEBREW_API_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles/api \
HOMEBREW_BOTTLE_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles \
brew install colima docker docker-compose
```

### ② 手动准备虚拟机镜像（关键一步）

`colima start` 会去 GitHub Releases 下载 332MB 的
`ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz`，国内会超时。改用 GitHub 反代下载、
校验后解压，再用 `--disk-image` 指定本地文件：

```bash
mkdir -p ~/.colima/images
curl -L --retry 3 \
  -o ~/.colima/images/ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz \
  "https://ghfast.top/https://github.com/abiosoft/colima-core/releases/download/v0.10.4/ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz"

# 校验（必须一致，否则镜像不可信）
shasum -a 512 ~/.colima/images/ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz
# 期望：32242674b046b5057e60c4aba334b51e3665f05412cda89ed081cc2de153ae5c41f6b105b5c442cbe48d78e2cc21e9ba1950e406b6fb4fc2fd1dd2259240abbd

gzip -dc ~/.colima/images/ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz \
  > ~/.colima/images/ubuntu-24.04-arm64-docker.raw
```

### ③ 用本地镜像启动

```bash
colima start \
  --disk-image ~/.colima/images/ubuntu-24.04-arm64-docker.raw \
  --force-disk-image \
  --cpu 4 --memory 8 --disk 60
```

`--force-disk-image` 的作用：解压后的 raw 与官方 gz 的哈希必然不同，不加会被
`disk image must be downloaded from ... hash failure` 拦下。
**只有在你亲自校验过 ② 的 SHA-512 时才能用它**——它等于跳过完整性检查。

启动成功后，后续 `colima start` 不再需要 `--disk-image`，配置已存进 `~/.colima/default/`。

### ④ 配镜像加速（Docker Hub 被墙）

实测仍可用的公共加速器（2026-10）：`docker.m.daocloud.io`、`docker.1ms.run`。
探测：`curl -o /dev/null -w "%{http_code}" https://<域名>/v2/`，
返回 **401 表示活着**（要求鉴权），000/404 表示已失效。

```bash
cp ~/.colima/default/colima.yaml ~/.colima/default/colima.yaml.bak.$(date +%s)
# 把文件里的 docker: {} 改成：
# docker:
#   registry-mirrors:
#     - https://docker.m.daocloud.io
#     - https://docker.1ms.run
colima restart
docker info | grep -A3 "Registry Mirrors"
docker run --rm hello-world     # 应输出 Hello from Docker!
```

回滚：`cp ~/.colima/default/colima.yaml.bak.<时间戳> ~/.colima/default/colima.yaml && colima restart`

### 装完的实际版本

- Docker Client 29.9.1 / Server 29.5.2（linux/arm64）
- Docker Compose 5.6.0
- 虚拟机：4 CPU / 8GB / 60GB，mountType virtiofs
- 磁盘：raw 镜像 3.5GB，另可删 `.raw.gz` 省 317MB

---

## 6. 装完之后

三条验证命令（本机已全部通过）：

```bash
docker version                 # Client + Server 都有
docker compose version         # v2.x
docker run --rm hello-world    # 应输出 Hello from Docker!
```

接下来就可以做容器化编排了：`backend/Dockerfile`（Maven 多阶段 + 非 root）、
`frontend/Dockerfile`（Node 构建 + Nginx 托管并反代 `/api`）、
`docker-compose.yml`（mysql 9.x 挂 `db/schema.sql` 初始化 + backend + frontend + ollama）。

**关于 Ollama 进容器**：好处是到 Linux 服务器部署时整齐、一条命令全栈起来。
需要知道一点——macOS 上容器拿不到 Metal GPU，Ollama 在容器里是纯 CPU 推理，
向量化会比原生 Ollama 慢一个数量级（重建 695 篇笔记的向量会明显变久），
日常单篇增量重建影响不大。

觉得慢就切回宿主机：把 `.env` 里 `EMBEDDING_BASE_URL` 指向
`http://host.docker.internal:11434`，compose 里不启 ollama 服务即可，不用改代码。
