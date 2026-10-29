import { expect, test, type Page } from '@playwright/test'

/**
 * 冒烟测试：只守「坏了一眼看不出」的路径。
 *
 * 不追求覆盖率。要抓的是这类问题：
 * - 页面能打开，但所有接口 403/501/500（CORS 漏登记、静态服务器不代理 API）
 * - 登录后跳转错乱、列表永远停在骨架、详情页空白
 *
 * 账号密码从环境变量读，不落盘：
 *   E2E_ADMIN_USER=admin E2E_ADMIN_PASSWORD=xxx npm run e2e
 */
const USERNAME = process.env.E2E_ADMIN_USER ?? 'admin'
const PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? ''

test.skip(!PASSWORD, '缺少 E2E_ADMIN_PASSWORD，跳过冒烟测试')

/**
 * 收集接口失败。
 *
 * 这是整组用例里最重要的一条断言：页面「看起来正常」不算通过，
 * 背后的 /api/** 不许有 4xx/5xx（401 除外——那是未登录用例的预期）。
 */
function trackApiFailures(page: Page): string[] {
  const failures: string[] = []
  page.on('response', (response) => {
    const url = response.url()
    if (!url.includes('/api/')) return
    const status = response.status()
    if (status >= 400 && status !== 401) failures.push(`${status} ${response.request().method()} ${url}`)
  })
  page.on('requestfailed', (request) => {
    if (request.url().includes('/api/')) failures.push(`NETFAIL ${request.method()} ${request.url()}`)
  })
  return failures
}

async function login(page: Page) {
  await page.goto('/login')
  await page.fill('#username', USERNAME)
  await page.fill('#password', PASSWORD)
  await page.click('button[type="submit"]')
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15_000 })
}

test.describe('未登录', () => {
  test('访问受保护页面会被送到登录页，并记住原地址', async ({ page }) => {
    await page.goto('/notes')
    await expect(page).toHaveURL(/\/login/)
    expect(page.url()).toContain('redirect')
    // 登录页本身不该报错
    await expect(page.locator('.login-error')).toHaveCount(0)
  })

  test('公开博客页不需要登录', async ({ page }) => {
    const failures = trackApiFailures(page)
    await page.goto('/blog')
    await expect(page.locator('body')).toBeVisible()
    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })
})

test.describe('登录后', () => {
  test('登录 → 笔记列表加载完成（不是永远停在骨架）', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)

    await page.goto('/notes')
    // 骨架必须在合理时间内消失，否则说明接口挂了但页面没报错
    await expect(page.locator('.skeleton')).toHaveCount(0, { timeout: 15_000 })
    await expect(page.locator('body')).not.toContainText('正在读取')

    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })

  test('打开一篇笔记，正文渲染出来', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)

    await page.goto('/notes')
    const firstNote = page.locator('a[href^="/notes/"]').first()
    await expect(firstNote).toBeVisible({ timeout: 15_000 })
    await firstNote.click()

    await expect(page.locator('h1')).not.toBeEmpty({ timeout: 15_000 })
    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })

  test('设置页显示依赖健康状态', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)

    await page.goto('/settings')
    await expect(page.locator('body')).toContainText('运行状态', { timeout: 15_000 })
    await expect(page.locator('body')).toContainText(/数据库/)

    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })

  test('分类栏点标签能筛出子集（不是只换了标签样式）', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)

    await page.goto('/notes')
    const chip = page.locator('.tag-chip').first()
    await expect(chip).toBeVisible({ timeout: 15_000 })

    const before = (await page.locator('.page-note').innerText()).trim()
    await chip.click()

    // 选中的标签要有视觉状态，且列表确实变窄了——只变样式不改结果是最容易漏的 bug
    await expect(page.locator('.tag-chip.tag-on')).toHaveCount(1)
    await expect(page.locator('.page-note')).not.toHaveText(before)

    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })
})
