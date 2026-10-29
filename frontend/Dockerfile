# 前端镜像：Node 构建静态产物，Nginx 托管并反代 /api

FROM node:22-alpine AS builder
WORKDIR /app
# 国内网络走 npmmirror，先复制 lock 文件利用层缓存
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# 直接落成 default.conf，不走 nginx 官方镜像的 templates/envsubst 机制：
# 生产 compose 会把 deploy/nginx/edge.conf 只读挂载到同一路径做云端加固，
# 若这里用 template，启动时 envsubst 要写挂载点会报 Read-only file system 崩溃循环。
# （原来留 template 是为以后加环境变量，真需要时改回 templates/*.template 并同步去掉挂载）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
