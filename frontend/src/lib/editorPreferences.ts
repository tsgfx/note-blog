/** 编辑器本地偏好。键名、默认值与容错口径只能由这里拥有。 */

export type EditorModePreference = 'rich' | 'source'

const MODE_KEY = 'note-editor-mode'
const WRAP_KEY = 'note-editor-code-wrap'

export function loadEditorMode(): EditorModePreference {
  return localStorage.getItem(MODE_KEY) === 'source' ? 'source' : 'rich'
}

export function saveEditorMode(mode: EditorModePreference): void {
  localStorage.setItem(MODE_KEY, mode)
}

export function loadCodeWrapPreference(): boolean {
  return localStorage.getItem(WRAP_KEY) === '1'
}

export function saveCodeWrapPreference(enabled: boolean): void {
  try {
    localStorage.setItem(WRAP_KEY, enabled ? '1' : '0')
  } catch {
    // 隐私模式下 localStorage 可能不可写，不影响编辑
  }
}
