/**
 * 可拖拽宽度的通用控制器。
 *
 * 拥有四件容易复制错的事情：视口约束下的 clamp、pointermove/pointerup 生命周期、
 * 拖拽中的 body 状态class、宽度持久化/恢复。调用方只负责两件事：
 * 从指针 x 坐标折算业务宽度，以及把结果应用到自己的 CSS/响应式状态。
 *
 * `setWidth(null)` 表示恢复默认；默认值仍归调用方的 CSS/响应式状态所有，
 * 控制器不复制一份默认宽度常量。
 */
export interface ResizableWidthOptions {
  storageKey: string
  minWidth: number
  maxWidth: number
  /** 给主内容保留的最小横向空间；视口太窄时仍允许回到 minWidth，避免把手消失 */
  viewportReserve: number
  /** 小于这个视口宽度时禁用拖拽（移动端通常改为全宽布局） */
  minViewportWidth: number
  resizingClass: string
  measureWidth(pointerX: number): number
  setWidth(width: number | null): void
}

export interface ResizableWidthController {
  /** null = 尚未显式设置，当前展示的是调用方自己的默认宽度 */
  readonly width: number | null
  pointerDown(event: PointerEvent): void
  reset(): void
  restore(): void
  destroy(): void
}

export function createResizableWidth(options: ResizableWidthOptions): ResizableWidthController {
  let width: number | null = null
  let dragging = false

  function clamp(px: number): number {
    const viewportMax = window.innerWidth - options.viewportReserve
    const upper = Math.max(options.minWidth, Math.min(options.maxWidth, viewportMax))
    return Math.round(Math.min(Math.max(px, options.minWidth), upper))
  }

  function apply(px: number): number {
    const next = clamp(px)
    if (next === width) return next
    width = next
    options.setWidth(width)
    return next
  }

  function onMove(event: PointerEvent) {
    apply(options.measureWidth(event.clientX))
  }

  function finish(event: PointerEvent) {
    if (!dragging) return
    dragging = false
    document.body.classList.remove(options.resizingClass)
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    const finalWidth = apply(options.measureWidth(event.clientX))
    try {
      localStorage.setItem(options.storageKey, String(finalWidth))
    } catch {
      // 隐私模式下只是不记住宽度，不影响本次调整
    }
  }

  function onUp(event: PointerEvent) {
    finish(event)
  }

  return {
    get width() {
      return width
    },

    pointerDown(event: PointerEvent) {
      if (window.innerWidth < options.minViewportWidth) return
      event.preventDefault()
      dragging = true
      document.body.classList.add(options.resizingClass)
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
    },

    reset() {
      width = null
      options.setWidth(null)
      try {
        localStorage.removeItem(options.storageKey)
      } catch {
        // 同上
      }
    },

    restore() {
      try {
        const saved = Number(localStorage.getItem(options.storageKey))
        if (saved > 0) apply(saved)
      } catch {
        // 读不到就用默认宽度
      }
    },

    destroy() {
      dragging = false
      document.body.classList.remove(options.resizingClass)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    },
  }
}
