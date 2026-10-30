import { ref } from 'vue'
import type { Router } from 'vue-router'
import { createNote, type NoteDetail } from '../api/notes'
import { notify } from './feedback'

const DRAFT_STORAGE_KEY = 'note_blog_quick_capture_draft'

export const PRESET_TAGS = ['闪念', '灵感', '待办', '收件箱']

export const isOpen = ref(false)
export const content = ref('')
export const title = ref('')
export const showTitleInput = ref(false)
export const tags = ref<string[]>(['闪念'])
export const saving = ref(false)

/** 从草稿缓存恢复 */
export function loadDraft() {
  try {
    const raw = sessionStorage.getItem(DRAFT_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as { content?: string; title?: string; tags?: string[] }
      if (parsed.content) content.value = parsed.content
      if (parsed.title) {
        title.value = parsed.title
        showTitleInput.value = true
      }
      if (Array.isArray(parsed.tags) && parsed.tags.length > 0) {
        tags.value = parsed.tags
      }
    }
  } catch {
    // 忽略解析错误
  }
}

/** 临时持久化草稿，防止小屏幕误触关闭导致灵感丢失 */
export function persistDraft() {
  try {
    if (content.value.trim() || title.value.trim()) {
      sessionStorage.setItem(
        DRAFT_STORAGE_KEY,
        JSON.stringify({
          content: content.value,
          title: title.value,
          tags: tags.value,
        }),
      )
    } else {
      sessionStorage.removeItem(DRAFT_STORAGE_KEY)
    }
  } catch {
    // 隐私模式下静默
  }
}

export function clearDraft() {
  try {
    sessionStorage.removeItem(DRAFT_STORAGE_KEY)
  } catch {
    // 隐私模式下静默
  }
  content.value = ''
  title.value = ''
  showTitleInput.value = false
  tags.value = ['闪念']
}

/** 自动提取正文首行或时间戳作为标题 */
export function inferTitle(rawContent: string, explicitTitle?: string): string {
  if (explicitTitle && explicitTitle.trim()) {
    return explicitTitle.trim()
  }
  const lines = rawContent.split('\n')
  for (const line of lines) {
    const cleaned = line
      .replace(/^[#>\s*\-+]+/, '')
      .replace(/^\[[ xX]\]\s*/, '')
      .replace(/[`_~]/g, '')
      .trim()
    if (cleaned.length > 0) {
      return cleaned.length > 40 ? cleaned.slice(0, 40) + '…' : cleaned
    }
  }
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `闪念 ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
}

export function openQuickCapture(initialText?: string) {
  loadDraft()
  if (initialText !== undefined && initialText.trim()) {
    content.value = initialText.trim()
  }
  isOpen.value = true
}

export function closeQuickCapture() {
  persistDraft()
  isOpen.value = false
}

export function toggleQuickCapture() {
  if (isOpen.value) {
    closeQuickCapture()
  } else {
    openQuickCapture()
  }
}

export async function submitQuickCapture(options: {
  router?: Router
  navigateToEdit?: boolean
}): Promise<NoteDetail | null> {
  const body = content.value.trim()
  if (!body) {
    notify('请输入闪念内容', 'error')
    return null
  }

  const finalTitle = inferTitle(body, title.value)
  const finalTags = tags.value.length > 0 ? tags.value : ['闪念']

  saving.value = true
  try {
    const note = await createNote({
      title: finalTitle,
      contentMd: body,
      tags: finalTags,
    })

    clearDraft()
    isOpen.value = false

    if (options.navigateToEdit && options.router) {
      notify('已存为草稿，正在打开完整编辑器…', 'success')
      await options.router.push(`/editor/${note.id}`)
    } else {
      notify(`已保存到闪念收集箱：「${finalTitle}」`, 'success')
      // 如果当前就在笔记列表，通过自定义事件通知列表刷新
      window.dispatchEvent(new CustomEvent('notes-reload'))
    }
    return note
  } catch (e) {
    notify(e instanceof Error ? e.message : '保存失败，请稍后重试', 'error')
    return null
  } finally {
    saving.value = false
  }
}
