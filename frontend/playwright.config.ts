import { defineConfig, devices } from '@playwright/test'

/**
 * 冒烟测试配置。
 *
 * 刻意**不配 webServer**：这些用例跑在真实栈上（nginx + 后端 + MySQL），
 * 而不是 vite 预览一个只有前端的空壳。理由是「页面能开、接口全挂」正是要抓的
 * 那类问题——2026-10-23 就出现过静态服务器下 GET 404 / POST 501，页面看着正常。
 *
 * 用法：
 *   docker compose up -d           # 先把栈跑起来
 *   E2E_ADMIN_PASSWORD=xxx npm run e2e
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8088'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 8_000 },
  // 登录态与数据库是共享状态，并发跑会互相干扰
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
