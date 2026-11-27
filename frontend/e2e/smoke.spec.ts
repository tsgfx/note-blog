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

test.describe('编辑器代码块', () => {
  test('能选代码语言、切换自动换行，并把语言写进 markdown 围栏', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)
    await page.goto('/editor/new')
    await expect(page.locator('.rich-body .tiptap')).toBeVisible({ timeout: 15_000 })

    await page.locator(".toolbar button[title='代码块']").first().click()
    await page.keyboard.type('print(1)')
    await page.selectOption('.toolbar-lang', 'python')
    await expect(page.locator('.rich-body pre code').first()).toHaveClass(/language-python/)

    // 默认按原样横向滚动，开了「自动换行」才折行
    const whiteSpace = () => page.evaluate(() => getComputedStyle(document.querySelector('.rich-body pre')!).whiteSpace)
    expect(await whiteSpace()).toBe('pre')
    await page.locator("button[title^='代码块自动换行']").click()
    expect(await whiteSpace()).toBe('pre-wrap')

    // 切到源码模式，围栏要带上语言，否则阅读页没有高亮
    await page.locator('.mode-switch button', { hasText: 'Markdown' }).click()
    await expect(page.locator('.frame.source textarea')).toHaveValue(/```python/)

    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })
})

test.describe('管理员工具', () => {
  test('未登录访问账号整理会被送到登录页，并记住原地址', async ({ page }) => {
    await page.goto('/admin/tools/accounts')
    await expect(page).toHaveURL(/\/login/)
    // vue-router 的 query 序列化不转义 '/'，所以断言原样的路径
    expect(page.url()).toContain('redirect=/admin/tools/accounts')
  })

  test('账号整理：粘贴四条格式，能拆成卡片、原始行、JSON 与 CSV', async ({ page }) => {
    const failures = trackApiFailures(page)
    await login(page)

    await page.goto('/admin/tools/accounts')
    await expect(page.locator('h1.page-title')).toHaveText('账号整理')

    const raw = [
      'alice@example.com----P@ss w0rd----alice.backup@example.com----123456',
      '13800138000----carolpass----carol@example.com',
      '{"email":"bob@example.com","password":"bobpass","recovery_email":"bob.backup@example.com","totp_secret":"JBSWY3DPEHPK3PXP"}',
      '账号：dora@example.com 密码是 Zz9988 辅助邮箱：dora.backup@example.com',
    ].join('\n\n')

    await page.fill('#account-raw-input', raw)
    await expect(page.locator('.acc-card')).toHaveCount(4, { timeout: 10_000 })

    // 第 1 条：密码里的空格与 @ 都要原样保留
    const first = page.locator('.acc-card').first()
    await expect(first.locator('.acc-field-input').nth(0)).toHaveValue('alice@example.com')
    await expect(first.locator('.acc-field-input').nth(1)).toHaveValue('P@ss w0rd')
    await expect(first.locator('.acc-field-input').nth(2)).toHaveValue('alice.backup@example.com')
    await expect(first.locator('.acc-field-input').nth(3)).toHaveValue('123456')

    // 第 2 条：手机号当账号
    await expect(page.locator('.acc-card').nth(1).locator('.acc-field-input').nth(0)).toHaveValue('13800138000')

    // 第 3 条：TOTP 密钥能算出当前验证码（6 位数字）
    await expect(page.locator('.acc-card').nth(2).locator('.acc-totp-code')).toHaveText(/^\d{6}$/)

    // 输出面板默认打码，点卡片上的「显示」才还原明文
    await page.locator('.acc-card').first().locator('button', { hasText: '显示' }).click()

    // 原始行逐字保留
    await expect(page.locator('.acc-pre').first()).toContainText(
      'alice@example.com----P@ss w0rd----alice.backup@example.com----123456',
    )

    // JSON / CSV 面板拿真值（不走打码显示）来解析
    const jsonText = await page.evaluate(() => document.querySelectorAll('.acc-pre')[1]?.textContent ?? '')
    expect(JSON.parse(jsonText)).toHaveLength(4)
    const csvText = await page.evaluate(() => document.querySelectorAll('.acc-pre')[2]?.textContent ?? '')
    expect(csvText.split('\n').length).toBe(5)

    await page.click('button:has-text("清空")')
    await expect(page.locator('#account-raw-input')).toHaveValue('')
    await expect(page.locator('.acc-card')).toHaveCount(0)

    expect(failures, `接口失败：\n${failures.join('\n')}`).toEqual([])
  })
})
