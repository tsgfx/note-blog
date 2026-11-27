/** 写入剪贴板。非安全上下文（如局域网 IP 访问）下回退到 execCommand。 */
export async function writeClipboard(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      /* 继续尝试回退方案 */
    }
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  let copied: boolean
  try {
    copied = document.execCommand('copy')
  } catch {
    copied = false
  }
  area.remove()
  return copied
}

/** 全局委托：页面里任何代码块的「复制 / 换行」按钮都能工作，包括 v-html 渲染的内容。 */
export function installCodeCopy() {
  document.addEventListener('click', async (event) => {
    const target = event.target as Element | null

    // 自动换行：逐块切换，长行折行后不再需要横向拖动（阅读页与编辑器预览共用）
    const wrapButton = target?.closest<HTMLButtonElement>('.code-wrap')
    if (wrapButton) {
      const windowEl = wrapButton.closest('.code-window')
      const on = windowEl?.classList.toggle('is-wrapped') ?? false
      wrapButton.setAttribute('aria-pressed', on ? 'true' : 'false')
      return
    }

    const button = target?.closest<HTMLButtonElement>('.code-copy')
    if (!button) return
    const code = button.closest('.code-window')?.querySelector('code')?.textContent ?? ''
    const ok = await writeClipboard(code.replace(/\n$/, ''))
    button.textContent = ok ? '已复制' : '复制失败'
    button.dataset.state = ok ? 'done' : 'error'
    window.setTimeout(() => {
      button.textContent = '复制'
      delete button.dataset.state
    }, 1600)
  })
}
