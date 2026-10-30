/**
 * 页面级 SEO 信息。
 *
 * <p>站点是纯前端渲染，爬虫与社交平台的抓取大多不执行 JS，所以这里改的是
 * 「已经在浏览器里的人在看的页面」：标签页标题、历史记录、以及会读 DOM 的抓取器。
 * 真正的首屏 OG 卡片要靠预渲染，这个是顺手的改进而不是替代方案。
 */
const SITE = '一隅'

function setMeta(selector: string, attr: 'name' | 'property', key: string, value: string) {
  let el = document.head.querySelector<HTMLMetaElement>(selector)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', value)
}

/** 正文转纯文本，用于没有摘要时兜底生成 description */
export function plainText(markdown: string, limit = 120): string {
  const text = markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*_`~-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > limit ? `${text.slice(0, limit)}…` : text
}

export function applyArticleSeo(title: string, description: string) {
  document.title = `${title} · ${SITE}`
  const desc = description.trim()
  if (!desc) return
  setMeta('meta[name="description"]', 'name', 'description', desc)
  setMeta('meta[property="og:title"]', 'property', 'og:title', `${title} · ${SITE}`)
  setMeta('meta[property="og:description"]', 'property', 'og:description', desc)
  setMeta('meta[property="og:type"]', 'property', 'og:type', 'article')
}
