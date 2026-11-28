/**
 * 管理端列表里的目录展示名。
 *
 * 这不是简单截取路径：已经按目录筛选后，完整路径重复出现没有信息量，
 * 因此只显示相对当前目录的剩余部分；直接位于当前目录时显示「本目录」。
 */
export function noteFolderLabel(sourcePath: string | null | undefined, activePath: string): string {
  if (!sourcePath) return '新建笔记'
  const parts = sourcePath.split('/')
  parts.pop()
  if (!parts.length) return '根目录'

  const scope = activePath ? activePath.split('/') : []
  const inScope = scope.length > 0 && scope.every((segment, index) => parts[index] === segment)
  const relative = inScope ? parts.slice(scope.length) : parts
  return relative.join(' / ') || '本目录'
}
