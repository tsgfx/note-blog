import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'

const md = new MarkdownIt({
  html: false,
  linkify: true,
})

// 代码块渲染成 macOS 窗口样式：标题栏（三个圆点、语言名、复制按钮）+ 代码区。
const LANGUAGE_ALIAS: Record<string, string> = { shell: 'bash', sh: 'bash', zsh: 'bash', console: 'bash' }

/**
 * 正文图片懒加载。
 *
 * <p>库里有 2236 张图、共 256MB，长笔记常带十几张截图。默认行为是一进页面就全部下载，
 * 首屏要等一堆根本没滚到的图。`loading="lazy"` 让浏览器只取视口附近的，
 * `decoding="async"` 避免解码阻塞排版。
 */
md.renderer.rules.image = (tokens, index, options, _env, self) => {
  tokens[index].attrSet('loading', 'lazy')
  tokens[index].attrSet('decoding', 'async')
  return self.renderToken(tokens, index, options)
}

md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const lang = token.info.trim().split(/\s+/)[0] ?? ''
  const code = token.content.replace(/\n$/, '')
  const grammar = LANGUAGE_ALIAS[lang.toLowerCase()] ?? lang
  const known = grammar !== '' && hljs.getLanguage(grammar) !== undefined
  const body = known
    ? hljs.highlight(code, { language: grammar, ignoreIllegals: true }).value
    : md.utils.escapeHtml(code)
  const label = md.utils.escapeHtml(lang)
  return [
    '<figure class="code-window">',
    '<div class="code-bar">',
    '<span class="code-dots" aria-hidden="true"><i></i><i></i><i></i></span>',
    `<span class="code-lang">${label}</span>`,
    '<button class="code-copy" type="button" aria-label="复制代码">复制</button>',
    '</div>',
    `<pre><code class="hljs language-${label}">${body}</code></pre>`,
    '</figure>',
    '',
  ].join('\n')
}

export function renderMarkdown(source: string): string {
  return md.render(source)
}

export interface OutlineItem {
  id: string
  level: number
  text: string
}

/** 给渲染后的标题补上 id，并返回大纲。 */
export function buildOutline(root: HTMLElement): OutlineItem[] {
  const items: OutlineItem[] = []
  const used = new Map<string, number>()
  root.querySelectorAll<HTMLElement>('h1, h2, h3, h4').forEach((el, index) => {
    const text = el.textContent?.trim() || ''
    const base = text.replace(/\s+/g, '-').replace(/[^\w\u4e00-\u9fff-]/g, '') || `section-${index}`
    const count = used.get(base) ?? 0
    used.set(base, count + 1)
    const id = count === 0 ? base : `${base}-${count}`
    el.id = id
    items.push({ id, level: Number(el.tagName.slice(1)), text })
  })
  return items
}
