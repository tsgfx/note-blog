/**
 * 公开文章的本地阅读足迹。
 *
 * 这份顺序会直接影响「为你推荐」：最近阅读在前，去重，最多保留 10 篇。
 * 因此键名、解析容错、排序和截断规则只能由这里拥有。
 */
const STORAGE_KEY = 'notesblog_read_history'
const MAX_ENTRIES = 10

export function getReadingHistory(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const list: unknown = JSON.parse(raw)
    return Array.isArray(list) ? list.filter((item): item is string => typeof item === 'string' && item.length > 0) : []
  } catch {
    // 隐私模式或历史数据损坏时，推荐退回无足迹状态，而不是让列表页报错
    return []
  }
}

export function recordReadingHistory(slug: string): void {
  if (!slug) return
  try {
    const updated = [slug, ...getReadingHistory().filter((item) => item !== slug)].slice(0, MAX_ENTRIES)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
  } catch {
    // 隐私模式下静默；阅读足迹只是增强体验，不该阻断阅读
  }
}
