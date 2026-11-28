/**
 * 展示层日期格式。这里只收本地确实使用的 ISO 字符串展示格式，
 * 不做时区转换，也不试图成为通用日期库。
 */
export function isoDate(value?: string): string {
  return value ? value.slice(0, 10) : ''
}

export function isoYear(value?: string): string {
  return value ? value.slice(0, 4) : ''
}

/** MM-DD：博客归档列表的紧凑日期。 */
export function monthDayCompact(value?: string): string {
  return value ? `${value.slice(5, 7)}-${value.slice(8, 10)}` : ''
}

/** MM / DD：首页笔记目录的编辑式日期。 */
export function monthDaySpaced(value?: string): string {
  return value ? value.slice(5, 10).replace('-', ' / ') : ''
}
