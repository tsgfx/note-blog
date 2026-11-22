/**
 * 渲染期异常的兜底提示。
 *
 * <p>Vue 组件在渲染或生命周期里抛错时，Vue 会卸载那棵子树，用户看到的就是白屏，
 * 控制台里一行红字只有打开 DevTools 才看得见——既不知道出了什么事，也不知道该做什么。
 * 这里把它变成页面顶部一条可见的提示：刷新一次大概率就好（多数是一时半刻的接口/网络问题），
 * 反复出现就说明是代码问题。
 *
 * <p><b>为什么用内联样式而不是 CSS 类：</b>这条路径可能在 style.css 还没生效时就触发，
 * 而且很可能就是某个组件崩了——不能假设任何 UI 组件、变量、主题还可用。
 */
const BANNER_ID = 'app-fatal-banner'

/** 同一个页面只挂一条，重复抛错不再叠加 */
export function reportFatalError(error: unknown, info?: string): void {
  console.error('[app] 未处理的异常', info ?? '', error)
  if (document.getElementById(BANNER_ID)) return

  const bar = document.createElement('div')
  bar.id = BANNER_ID
  bar.setAttribute('role', 'alert')
  Object.assign(bar.style, {
    position: 'fixed',
    inset: '0 0 auto 0',
    zIndex: '9999',
    display: 'flex',
    gap: '12px',
    alignItems: 'center',
    justifyContent: 'center',
    flexWrap: 'wrap',
    padding: '10px 16px',
    font: '14px/1.5 system-ui, -apple-system, "PingFang SC", sans-serif',
    color: '#7c2d12',
    background: '#fef3c7',
    borderBottom: '1px solid #fcd34d',
  })

  const text = document.createElement('span')
  text.textContent = '页面出错了。刷新一次通常就好；反复出现请把控制台的报错发给我。'
  bar.appendChild(text)

  const reload = document.createElement('button')
  reload.type = 'button'
  reload.textContent = '刷新'
  Object.assign(reload.style, {
    font: 'inherit',
    padding: '2px 12px',
    border: '1px solid #d97706',
    borderRadius: '6px',
    color: '#7c2d12',
    background: '#fff',
    cursor: 'pointer',
  })
  reload.addEventListener('click', () => window.location.reload())
  bar.appendChild(reload)

  document.body.prepend(bar)
}
