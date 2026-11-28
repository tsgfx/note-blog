/** 管理端笔记列表的键盘快捷键决策。DOM focus/scroll 与路由跳转仍属于调用方。 */

export type NotesListKeyAction =
  | 'none'
  | 'focus-search'
  | 'move-focus-down'
  | 'move-focus-up'
  | 'open-focused'
  | 'toggle-focused-selection'
  | 'clear-focus-and-selection'
  | 'clear-query-and-blur'
  | 'blur-search'

export interface NotesListKeyInput {
  key: string
  typing: boolean
  hasQuery: boolean
  dialogOpen: boolean
  modified: boolean
  focused: boolean
}

/** 只决定动作，不执行副作用；否则键盘规则会被 DOM 细节淹没且无法单测。 */
export function notesListKeyAction(input: NotesListKeyInput): NotesListKeyAction {
  if (input.dialogOpen) return 'none'

  if (input.typing) {
    if (input.key !== 'Escape') return 'none'
    return input.hasQuery ? 'clear-query-and-blur' : 'blur-search'
  }

  if (input.modified) return 'none'
  if (input.key === '/') return 'focus-search'
  if (input.key === 'j' || input.key === 'ArrowDown') return 'move-focus-down'
  if (input.key === 'k' || input.key === 'ArrowUp') return 'move-focus-up'
  if (input.key === 'Enter') return input.focused ? 'open-focused' : 'none'
  if (input.key === 'x') return input.focused ? 'toggle-focused-selection' : 'none'
  if (input.key === 'Escape') return 'clear-focus-and-selection'
  return 'none'
}
