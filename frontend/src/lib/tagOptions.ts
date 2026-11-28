/**
 * 管理端标签筛选栏的展示顺序。
 *
 * 规则不是简单按热度排序：已选标签必须先留在可见区域，搜索时不能因为
 * 收起数量限制而把命中项藏起来。这个顺序直接影响用户能否理解并撤销筛选。
 */
export function visibleTagOptions<T extends { name: string }>(
  tags: T[],
  activeNames: string[],
  query: string,
  expanded: boolean,
  collapsedLimit: number,
): T[] {
  const picked = tags.filter((tag) => activeNames.includes(tag.name))
  const rest = tags.filter((tag) => !activeNames.includes(tag.name))
  const q = query.trim().toLowerCase()

  if (q) return [...picked, ...rest.filter((tag) => tag.name.toLowerCase().includes(q))]
  if (expanded) return [...picked, ...rest]
  return [...picked, ...rest.slice(0, collapsedLimit)]
}
