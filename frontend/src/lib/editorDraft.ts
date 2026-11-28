/**
 * 笔记编辑器的本机草稿存储。
 *
 * 这里拥有存储键、序列化结构和损坏数据兜底；视图只决定何时写入、
 * 是否 dirty，以及恢复前是否询问用户。草稿只是崩溃兜底，不是服务端同步状态。
 */
const DRAFT_KEY = 'note-editor-draft-v1'

export interface EditorDraft {
  id: string
  title: string
  summary: string
  tagsText: string
  contentMd: string
  at: string
}

export type EditorDraftInput = Omit<EditorDraft, 'at'>

export function editorDraftId(slug: string): string {
  return slug || 'new'
}

/** 写入草稿。返回服务端无关的本机时间戳；隐私模式/配额满时返回 null。 */
export function writeEditorDraft(input: EditorDraftInput): string | null {
  try {
    const at = new Date().toISOString()
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ ...input, at }))
    return at
  } catch {
    return null
  }
}

export function loadEditorDraft(): EditorDraft | null {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object') return null
    const draft = value as Record<string, unknown>
    if (
      typeof draft.id !== 'string' ||
      typeof draft.title !== 'string' ||
      typeof draft.summary !== 'string' ||
      typeof draft.tagsText !== 'string' ||
      typeof draft.contentMd !== 'string' ||
      typeof draft.at !== 'string'
    ) {
      return null
    }
    return draft as unknown as EditorDraft
  } catch {
    return null
  }
}

export function clearEditorDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // 私密模式下清除失败不影响继续编辑
  }
}
