/** 源码 Markdown 编辑器的纯文本编辑规则。DOM/focus 只属于调用方。 */

export interface TextEditResult {
  value: string
  selectionStart: number
  selectionEnd: number
}

/**
 * 在光标处插入一个独立块。
 *
 * 如果前面已有文字且不在行尾，先补换行；插入后总是以换行结束，
 * 这样图片、链接块不会和后续正文粘在同一行。
 */
export function insertBlockAtCursor(value: string, start: number, end: number, text: string): TextEditResult {
  const before = value.slice(0, start)
  const after = value.slice(end)
  const lead = before && !before.endsWith('\n') ? '\n' : ''
  const insert = `${lead}${text}\n`
  const caret = before.length + insert.length
  return {
    value: before + insert + after,
    selectionStart: caret,
    selectionEnd: caret,
  }
}

/** 用标记包住选区；没有选区就插入占位文字并选中它，方便直接改写。 */
export function surroundSelection(
  value: string,
  start: number,
  end: number,
  before: string,
  after: string,
  placeholder: string,
): TextEditResult {
  const picked = value.slice(start, end) || placeholder
  return {
    value: value.slice(0, start) + before + picked + after + value.slice(end),
    selectionStart: start + before.length,
    selectionEnd: start + before.length + picked.length,
  }
}

/**
 * 给选区覆盖到的行加前缀；已经有相同前缀的行不重复加。
 *
 * 注意这里故意保留历史语义：替换范围到 selection end 为止，
 * 不自动扩展到行尾。工具条按钮作用的是“选区覆盖的前缀区”，不是整段重排。
 */
export function prefixSelectedLines(value: string, start: number, end: number, prefix: string): TextEditResult {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  const block = value.slice(lineStart, end) || ''
  const next = block
    .split('\n')
    .map((line) => (line.startsWith(prefix) ? line : `${prefix}${line}`))
    .join('\n')
  return {
    value: value.slice(0, lineStart) + next + value.slice(end),
    selectionStart: lineStart,
    selectionEnd: lineStart + next.length,
  }
}
