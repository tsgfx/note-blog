import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * 后端地址。默认打本机 8080（mvn spring-boot:run）；
 * 想连容器里的后端就 `VITE_API_TARGET=http://localhost:8081 npm run dev`。
 */
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

/** dev 与 preview 要同一条代理规则，抽出来免得两处写得不一样 */
const proxy = {
  '/api': {
    target: apiTarget,
    changeOrigin: true,
  },
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    /**
     * 编辑器 chunk 约 640 kB（gzip 230 kB）：Tiptap 全家桶 + markdown 序列化。
     * 路由已是懒加载，只有进 /editor 才下载，不影响首屏；把阈值调到匹配实际，
     * 免得每次构建都刷一条无意义的告警。
     */
    chunkSizeWarningLimit: 700,
  },
  server: {
    // 不写 host 时只会绑 IPv6 回环 [::1]，出现「localhost 能开、127.0.0.1 连不上」的怪现象。
    // 显式绑 IPv4 回环，两种写法都能访问。
    host: '127.0.0.1',
    port: 5173,
    // 端口被占时直接报错，而不是悄悄换到 5174，让人对着旧地址发呆
    strictPort: true,
    proxy,
  },
  /**
   * preview 是按构建产物预览，同样必须把 /api 代理出去。
   *
   * <p>之前用 `python -m http.server` 预览 dist，那是纯静态服务器：
   * GET 接口全 404、POST 全 501，一点「提问」就报「请求失败（501）」。
   * 静态服务器替不了后端，这里把代理接上，preview 才能真的点得动。
   */
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy,
  },
})
