import { reactive } from 'vue'

/**
 * 目录树的展开状态。
 *
 * 放在模块级而不是组件里：从笔记详情返回列表时，目录应该还是上次展开的样子，
 * 否则每次回来都要重新点开三层才能接着看。刷新页面才重置——这个取舍是有意的，
 * 展开状态不值得写进 URL 或 localStorage（会让「分享链接」带上别人的浏览痕迹）。
 */
const expanded = reactive(new Set<string>())

export function isFolderOpen(path: string): boolean {
  return expanded.has(path)
}

export function toggleFolder(path: string): void {
  if (expanded.has(path)) expanded.delete(path)
  else expanded.add(path)
}

/**
 * 展开某个目录的所有祖先。
 *
 * 选中深层目录（或从 URL 带着 ?path= 进来）时必须调它：
 * 否则选中项藏在折叠着的父目录里，用户看到列表变了、左边却找不到高亮在哪。
 */
export function revealFolder(path: string): void {
  if (!path) return
  const segments = path.split('/')
  let cursor = ''
  for (const segment of segments.slice(0, -1)) {
    cursor = cursor ? `${cursor}/${segment}` : segment
    expanded.add(cursor)
  }
}

/** 折叠全部（切换「全部」时用，视觉上回到初始状态） */
export function collapseAll(): void {
  expanded.clear()
}
