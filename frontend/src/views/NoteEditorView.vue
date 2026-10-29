<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Editor, EditorContent } from '@tiptap/vue-3'
import { Extension, InputRule, textblockTypeInputRule } from '@tiptap/core'
import type { MarkType } from '@tiptap/pm/model'
import StarterKit from '@tiptap/starter-kit'
import Bold from '@tiptap/extension-bold'
import Italic from '@tiptap/extension-italic'
import Strike from '@tiptap/extension-strike'
import Code from '@tiptap/extension-code'
import CodeBlock from '@tiptap/extension-code-block'
import Link from '@tiptap/extension-link'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import { Table } from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableCell from '@tiptap/extension-table-cell'
import TableHeader from '@tiptap/extension-table-header'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { Markdown } from 'tiptap-markdown'
import { createNote, generateNoteMeta, getNote, updateNote, uploadImage, type NoteInput } from '../api/notes'
import { confirmAction, notify } from '../lib/feedback'
import { renderMarkdown } from '../lib/markdown'

const route = useRoute()
const router = useRouter()
const isNew = computed(() => route.name === 'editor-new')

/*
 * 双模式编辑器（「两者结合」）：
 *   rich   —— 语雀式富文本：所见即所得，markdown 快捷输入规则开箱即用
 *             （敲 "## " 变标题、"**x**" 变粗体、"- " 变列表、``` 变代码块），
 *             保存时由 tiptap-markdown 序列化回标准 markdown
 *   source —— 原来的裸 markdown 双栏（textarea + 实时预览），留给精确控制的场景
 * 两种模式共享同一份 contentMd，随时互切不丢内容。
 */
type EditorMode = 'rich' | 'source'
const MODE_KEY = 'note-editor-mode'
const mode = ref<EditorMode>(localStorage.getItem(MODE_KEY) === 'source' ? 'source' : 'rich')

watch(mode, (value) => {
  localStorage.setItem(MODE_KEY, value)
  if (value === 'rich') {
    // 从源码切回富文本：把最新 markdown 灌进编辑器（不触发 onUpdate，免得脏标记抖动）
    editor.value?.commands.setContent(contentMd.value, false)
    void nextTick(() => editor.value?.commands.focus('end'))
  } else {
    flushMarkdown()
  }
})

const loading = ref(false)
const saving = ref(false)
/** 保存成功后正在等服务端生成摘要与标签（比保存本身慢，按钮文案要区分开） */
const metaWorking = ref(false)
const uploading = ref(false)
const noteId = ref<number>()
const title = ref('')
const summary = ref('')
const tagsText = ref('')
const contentMd = ref('')
const savedSnapshot = ref('')
const finished = ref(false)
const bodyEl = ref<HTMLTextAreaElement>()
const previewEl = ref<HTMLElement>()
const fileInput = ref<HTMLInputElement>()
const syncScroll = ref(true)
const dragging = ref(false)

/** 防止"设 preview.scrollTop → 触发 scroll 事件 → 又反过来改 textarea"的回环 */
let syncing = false

const snapshot = computed(() => JSON.stringify([title.value, summary.value, tagsText.value, contentMd.value]))
const dirty = computed(() => savedSnapshot.value !== '' && snapshot.value !== savedSnapshot.value)
const preview = computed(() => renderMarkdown(contentMd.value))
const wordCount = computed(() => contentMd.value.replace(/\s+/g, '').length)

/* ───────── Tiptap 富文本编辑器 ───────── */

const editor = shallowRef<Editor>()
/** 编辑器状态版本号：每次事务 +1，让工具栏的 active/disabled 重新求值 */
const tick = ref(0)
let syncTimer: number | undefined

function getMarkdown(): string {
  const storage = editor.value?.storage as Record<string, { getMarkdown: () => string }> | undefined
  return storage?.markdown?.getMarkdown() ?? contentMd.value
}

/** 富文本 → markdown（防抖）：键入时全量序列化会浪费，攒 300ms */
function scheduleMarkdownSync() {
  window.clearTimeout(syncTimer)
  syncTimer = window.setTimeout(() => {
    contentMd.value = getMarkdown()
  }, 300)
}

function flushMarkdown() {
  window.clearTimeout(syncTimer)
  if (editor.value) contentMd.value = getMarkdown()
}

/* ───────── Markdown 快捷输入增强（中文友好） ─────────
 * Tiptap 官方的行内标记输入规则要求 **x** 前是行首或空白，
 * 中文语境（前面是汉字/标点）永远不触发；这里换成 lookbehind 宽松版。
 * 同时补上官方缺失的 - [ ] 任务清单规则。 */
function mdMarkInputRule(find: RegExp, getType: () => MarkType) {
  return new InputRule({
    find,
    handler: ({ state, range, match }) => {
      const inner = match[match.length - 1] as string
      if (!inner) return
      const { tr } = state
      const textStart = range.from + match[0].indexOf(inner)
      const textEnd = textStart + inner.length
      tr.delete(textEnd, range.to)
      tr.delete(range.from, textStart)
      tr.addMark(range.from, range.from + inner.length, getType().create())
      tr.removeStoredMark(getType())
    },
  })
}

const LenientBold = Bold.extend({
  addInputRules() {
    return [
      mdMarkInputRule(/(?<!\*)(\*\*(?!\s+\*\*)((?:[^*]+))\*\*(?!\s+\*\*))$/, () => this.type),
      mdMarkInputRule(/(?<!_)(__(?!\s+__)((?:[^_]+))__(?!\s+__))$/, () => this.type),
    ]
  },
})

const LenientItalic = Italic.extend({
  addInputRules() {
    return [
      mdMarkInputRule(/(?<!\*)(\*(?!\s+\*)((?:[^*]+))\*(?!\s+\*))$/, () => this.type),
      mdMarkInputRule(/(?<!_)(_([^_]+)_)$/, () => this.type),
    ]
  },
})

const LenientStrike = Strike.extend({
  addInputRules() {
    return [mdMarkInputRule(/(?<!~)(~~(?!\s+~~)((?:[^~]+))~~(?!\s+~~))$/, () => this.type)]
  },
})

const LenientCode = Code.extend({
  addInputRules() {
    return [mdMarkInputRule(/(?<!`)(`([^`]+)`)$/, () => this.type)]
  },
})

const TaskListShortcut = Extension.create({
  name: 'taskListShortcut',
  addInputRules() {
    return [
      // 习惯性连输 "- "/"* " 时不要残留字面量或生成嵌套列表：
      // 光标已在列表项里就直接吞掉；不在列表里则不改动 tr，回退给官方规则建列表。
      // 注意本扩展必须排在 StarterKit 之前，否则官方规则先生效就轮不到这里
      new InputRule({
        find: /^\s?[-+*]\s$/,
        handler: ({ state, range }) => {
          const { $from } = state.selection
          let inList = false
          for (let d = $from.depth; d > 0; d--) {
            const name = $from.node(d).type.name
            if (name === 'listItem' || name === 'taskItem') {
              inList = true
              break
            }
          }
          if (inList) state.tr.delete(range.from, range.to)
        },
      }),
      // "- [ ] "（列表里继续输）或任意位置输 "[ ] " 都能转任务清单；
      // 不加前置边界，因为中文（列表项已有文字）后面照样要能触发
      new InputRule({
        find: /\[([ xX])\]\s$/,
        handler: ({ state, range, match }) => {
          const checked = match[1].toLowerCase() === 'x'
          // 连着待办输 "- [x] " 时 "- " 会留在清单项里，一并吞掉
          let from = range.from
          if (state.doc.textBetween(Math.max(0, from - 2), from) === '- ') from -= 2
          state.tr.delete(from, range.to)
          queueMicrotask(() => {
            if (!editor.value) return
            // 已在任务清单里（比如连着输多个待办）不能再 toggle，否则整个清单被翻回去
            const { $from } = editor.value.state.selection
            let inTaskList = false
            for (let d = $from.depth; d > 0; d--) {
              if ($from.node(d).type.name === 'taskList') {
                inTaskList = true
                break
              }
            }
            if (inTaskList) {
              editor.value.commands.updateAttributes('taskItem', { checked })
            } else {
              editor.value.chain().toggleTaskList().updateAttributes('taskItem', { checked }).run()
            }
          })
        },
      }),
    ]
  },
})

const LenientCodeBlock = CodeBlock.extend({
  addInputRules() {
    return [
      // 官方规则要求 ``` 后必须跟空格才触发；放宽成敲完 ``` 直接转
      textblockTypeInputRule({ find: /^```([a-z]+)?$/, type: this.type }),
    ]
  },
})

function createEditor() {
  editor.value = new Editor({
    content: contentMd.value,
    extensions: [
      // 列表辅助规则必须排在 StarterKit 之前，否则官方 bulletList 规则先生效
      TaskListShortcut,
      StarterKit.configure({
        heading: { levels: [1, 2, 3, 4] },
        bold: false,
        italic: false,
        strike: false,
        code: false,
        codeBlock: false,
        // 历史快捷键交给 Tiptap，避免和浏览器/系统输入法打架的场景自己写
      }),
      LenientBold,
      LenientItalic,
      LenientStrike,
      LenientCode,
      LenientCodeBlock,
      TaskListShortcut,
      Link.configure({ openOnClick: false, autolink: true }),
      Image.configure({ inline: false, allowBase64: false }),
      Table.configure({ resizable: false }),
      TableRow,
      TableCell,
      TableHeader,
      TaskList,
      TaskItem.configure({ nested: true }),
      Placeholder.configure({ placeholder: '开始写正文 — 敲 "## " 出标题、"- " 出列表、"> " 出引用、``` 出代码块' }),
      Markdown.configure({
        html: false,
        // 与阅读页 markdown-it 的渲染口径保持一致（软换行不强制 <br>）
        breaks: false,
      }),
    ],
    editorProps: {
      attributes: { class: 'prose tiptap' },
      handlePaste: (_view, event) => {
        const file = [...(event.clipboardData?.files ?? [])].find((item) => item.type.startsWith('image/'))
        if (!file) return false
        void uploadAndInsert(file)
        return true
      },
      handleDrop: (_view, event, _slice, moved) => {
        const file = [...(event.dataTransfer?.files ?? [])].find((item) => item.type.startsWith('image/'))
        if (!file || moved) return false
        event.preventDefault()
        void uploadAndInsert(file)
        return true
      },
    },
    onUpdate: () => {
      tick.value++
      scheduleMarkdownSync()
    },
    onTransaction: () => {
      tick.value++
    },
    onCreate: () => {
      tick.value++
    },
    autofocus: 'end',
  })
  editor.value.on('transaction', () => {
    tick.value++
  })
}

onBeforeUnmount(() => {
  window.clearTimeout(syncTimer)
  editor.value?.destroy()
})

function fillFrom(note: { title: string; summary?: string; tags: string[]; contentMd: string }) {
  title.value = note.title
  summary.value = note.summary || ''
  tagsText.value = note.tags.join(', ')
  contentMd.value = note.contentMd
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  if (isNew.value) {
    contentMd.value = '# 标题\n\n'
    savedSnapshot.value = snapshot.value
  } else {
    loading.value = true
    try {
      const note = await getNote(String(route.params.slug))
      noteId.value = note.id
      fillFrom(note)
      savedSnapshot.value = snapshot.value
    } catch (e) {
      notify(e instanceof Error ? e.message : String(e), 'error')
    } finally {
      loading.value = false
    }
  }
  // 笔记内容就位后再建编辑器，避免 setContent 打架
  createEditor()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function parseTags(): string[] {
  return tagsText.value
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean)
}

async function save() {
  if (!title.value.trim()) {
    notify('标题不能为空', 'error')
    return
  }
  // 富文本模式下先把编辑器内容序列化回 markdown，再走统一保存
  flushMarkdown()
  if (!contentMd.value.trim()) {
    notify('正文不能为空', 'error')
    return
  }
  saving.value = true
  metaWorking.value = false
  const input: NoteInput = {
    title: title.value.trim(),
    summary: summary.value.trim(),
    contentMd: contentMd.value,
    tags: parseTags(),
  }
  try {
    const note = isNew.value ? await createNote(input) : await updateNote(noteId.value!, input)
    finished.value = true
    // 摘要/标签留白时让服务端自动补：等生成完再跳详情页，进去就是全的。
    // 生成失败不拦着保存结果，只是提示一句。
    if (!input.summary || !input.tags.length) {
      metaWorking.value = true
      try {
        await generateNoteMeta(note.id)
        notify('已保存，摘要与标签已自动生成')
      } catch (genErr) {
        notify(
          '已保存（摘要标签自动生成失败：' + (genErr instanceof Error ? genErr.message : String(genErr)) + '）',
          'error',
        )
      } finally {
        metaWorking.value = false
      }
    } else {
      notify('已保存')
    }
    router.push(`/notes/${note.slug}`)
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    saving.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    save()
  }
}

onBeforeRouteLeave(async () => {
  // 富文本的防抖同步可能还压着 300ms，离开前先冲一遍
  flushMarkdown()
  if (finished.value || !dirty.value) return true
  return confirmAction({
    title: '放弃未保存的修改？',
    message: '离开后，这次编辑的内容不会保存。',
    confirmText: '放弃修改',
    cancelText: '继续编辑',
    danger: true,
  })
})

/* ───────── 图片上传：选文件 / 粘贴 / 拖拽，两条模式共用 ───────── */

function pickImage() {
  fileInput.value?.click()
}

async function uploadAndInsert(file: File) {
  if (!file.type.startsWith('image/')) {
    notify('只支持图片文件', 'error')
    return
  }
  uploading.value = true
  try {
    const url = await uploadImage(file)
    const alt = file.name.replace(/\.[^.]+$/, '') || '图片'
    if (mode.value === 'rich' && editor.value) {
      editor.value.chain().focus().setImage({ src: url, alt }).run()
    } else {
      insertAtCursor(`![${alt}](${url})`)
    }
    notify('图片已插入')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    uploading.value = false
  }
}

async function onImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) await uploadAndInsert(file)
}

/** 从剪贴板直接粘贴截图（源码模式；富文本模式走 editorProps.handlePaste） */
async function onPaste(event: ClipboardEvent) {
  const file = [...(event.clipboardData?.files ?? [])].find((item) => item.type.startsWith('image/'))
  if (!file) return
  event.preventDefault()
  await uploadAndInsert(file)
}

function onDragOver(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  dragging.value = true
}

function onDragLeave(event: DragEvent) {
  const next = event.relatedTarget as Node | null
  if (next && (event.currentTarget as HTMLElement).contains(next)) return
  dragging.value = false
}

async function onDrop(event: DragEvent) {
  const file = [...(event.dataTransfer?.files ?? [])].find((item) => item.type.startsWith('image/'))
  dragging.value = false
  if (!file) return
  event.preventDefault()
  await uploadAndInsert(file)
}

/* ───────── 源码模式的文本操作 ───────── */

function insertAtCursor(text: string) {
  const el = bodyEl.value
  const start = el?.selectionStart ?? contentMd.value.length
  const end = el?.selectionEnd ?? start
  const before = contentMd.value.slice(0, start)
  const after = contentMd.value.slice(end)
  const lead = before && !before.endsWith('\n') ? '\n' : ''
  const insert = `${lead}${text}\n`
  contentMd.value = before + insert + after
  nextTick(() => {
    if (!el) return
    el.focus()
    const caret = before.length + insert.length
    el.setSelectionRange(caret, caret)
  })
}

/** 用标记包住选区；没有选区就插入占位文字并选中它，方便直接改写 */
function surround(before: string, after: string, placeholder: string) {
  const el = bodyEl.value
  const start = el?.selectionStart ?? contentMd.value.length
  const end = el?.selectionEnd ?? start
  const picked = contentMd.value.slice(start, end) || placeholder
  contentMd.value = contentMd.value.slice(0, start) + before + picked + after + contentMd.value.slice(end)
  nextTick(() => {
    if (!el) return
    el.focus()
    el.setSelectionRange(start + before.length, start + before.length + picked.length)
  })
}

/** 按行加前缀（标题、列表、引用都是这个模式） */
function prefixLines(prefix: string) {
  const el = bodyEl.value
  const start = el?.selectionStart ?? contentMd.value.length
  const end = el?.selectionEnd ?? start
  const lineStart = contentMd.value.lastIndexOf('\n', start - 1) + 1
  const block = contentMd.value.slice(lineStart, end) || ''
  const lines = block.split('\n')
  const next = lines.map((line) => (line.startsWith(prefix) ? line : `${prefix}${line}`)).join('\n')
  contentMd.value = contentMd.value.slice(0, lineStart) + next + contentMd.value.slice(end)
  nextTick(() => {
    el?.focus()
    el?.setSelectionRange(lineStart, lineStart + next.length)
  })
}

const sourceToolbar: { label: string; title: string; run: () => void }[] = [
  { label: 'H2', title: '二级标题', run: () => prefixLines('## ') },
  { label: 'B', title: '粗体', run: () => surround('**', '**', '粗体') },
  { label: 'I', title: '斜体', run: () => surround('*', '*', '斜体') },
  { label: '</>', title: '行内代码', run: () => surround('`', '`', 'code') },
  { label: '```', title: '代码块', run: () => surround('\n```\n', '\n```\n', '') },
  { label: '链接', title: '插入链接', run: () => surround('[', '](https://)', '文字') },
  { label: '列表', title: '无序列表', run: () => prefixLines('- ') },
  { label: '引用', title: '引用', run: () => prefixLines('> ') },
]

/**
 * 双栏滚动同步。按滚动比例映射而不是逐像素对齐——
 * Markdown 与渲染后的高度差得太多，逐像素只会让两边越走越远。
 */
function onBodyScroll() {
  if (!syncScroll.value || syncing) return
  const src = bodyEl.value
  const dst = previewEl.value
  if (!src || !dst) return
  const srcMax = src.scrollHeight - src.clientHeight
  const dstMax = dst.scrollHeight - dst.clientHeight
  if (srcMax <= 0 || dstMax <= 0) return
  syncing = true
  dst.scrollTop = (src.scrollTop / srcMax) * dstMax
  window.requestAnimationFrame(() => {
    syncing = false
  })
}

/* ───────── 富文本工具栏 ───────── */

function chain() {
  return editor.value!.chain().focus()
}

function addLink() {
  if (!editor.value) return
  const previous = editor.value.getAttributes('link').href as string | undefined
  const url = window.prompt('链接地址（留空则移除链接）', previous ?? 'https://')
  if (url === null) return
  if (url === '' || url === 'https://') {
    chain().extendMarkRange('link').unsetLink().run()
    return
  }
  chain().extendMarkRange('link').setLink({ href: url }).run()
}

interface ToolDef {
  label: string
  title: string
  active?: () => boolean
  disabled?: () => boolean
  run: () => void
}

const richToolbar: ToolDef[] = [
  { label: '↺', title: '撤销 (⌘Z)', run: () => chain().undo().run(), disabled: () => !editor.value!.can().undo() },
  { label: '↻', title: '重做 (⇧⌘Z)', run: () => chain().redo().run(), disabled: () => !editor.value!.can().redo() },
  {
    label: 'H2',
    title: '二级标题',
    run: () => chain().toggleHeading({ level: 2 }).run(),
    active: () => editor.value!.isActive('heading', { level: 2 }),
  },
  {
    label: 'H3',
    title: '三级标题',
    run: () => chain().toggleHeading({ level: 3 }).run(),
    active: () => editor.value!.isActive('heading', { level: 3 }),
  },
  {
    label: 'H4',
    title: '四级标题',
    run: () => chain().toggleHeading({ level: 4 }).run(),
    active: () => editor.value!.isActive('heading', { level: 4 }),
  },
  {
    label: 'B',
    title: '粗体 (⌘B)',
    run: () => chain().toggleBold().run(),
    active: () => editor.value!.isActive('bold'),
  },
  {
    label: 'I',
    title: '斜体 (⌘I)',
    run: () => chain().toggleItalic().run(),
    active: () => editor.value!.isActive('italic'),
  },
  {
    label: 'S',
    title: '删除线',
    run: () => chain().toggleStrike().run(),
    active: () => editor.value!.isActive('strike'),
  },
  {
    label: '</>',
    title: '行内代码',
    run: () => chain().toggleCode().run(),
    active: () => editor.value!.isActive('code'),
  },
  {
    label: '```',
    title: '代码块',
    run: () => chain().toggleCodeBlock().run(),
    active: () => editor.value!.isActive('codeBlock'),
  },
  {
    label: '❝',
    title: '引用',
    run: () => chain().toggleBlockquote().run(),
    active: () => editor.value!.isActive('blockquote'),
  },
  {
    label: '• 列表',
    title: '无序列表',
    run: () => chain().toggleBulletList().run(),
    active: () => editor.value!.isActive('bulletList'),
  },
  {
    label: '1. 列表',
    title: '有序列表',
    run: () => chain().toggleOrderedList().run(),
    active: () => editor.value!.isActive('orderedList'),
  },
  {
    label: '☑ 任务',
    title: '任务列表',
    run: () => chain().toggleTaskList().run(),
    active: () => editor.value!.isActive('taskList'),
  },
  { label: '🔗 链接', title: '插入/编辑链接', run: addLink, active: () => editor.value!.isActive('link') },
  { label: '🖼 图片', title: '插入图片（也可粘贴/拖拽）', run: pickImage },
  { label: '— ', title: '分割线', run: () => chain().setHorizontalRule().run() },
  {
    label: '⊞ 表格',
    title: '插入 3×3 表格（含表头）',
    run: () => chain().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(),
  },
  {
    label: '+ 行',
    title: '在下方插入表格行',
    run: () => chain().addRowAfter().run(),
    disabled: () => !editor.value!.can().addRowAfter(),
  },
  {
    label: '- 行',
    title: '删除当前表格行',
    run: () => chain().deleteRow().run(),
    disabled: () => !editor.value!.can().deleteRow(),
  },
  {
    label: '+ 列',
    title: '在右侧插入表格列',
    run: () => chain().addColumnAfter().run(),
    disabled: () => !editor.value!.can().addColumnAfter(),
  },
  {
    label: '- 列',
    title: '删除当前表格列',
    run: () => chain().deleteColumn().run(),
    disabled: () => !editor.value!.can().deleteColumn(),
  },
]

/** 工具栏求值都挂着 tick，事务后随响应式刷新；editor 未挂载时一律视为非激活/不可用 */
function isActive(tool: ToolDef): boolean {
  void tick.value
  if (!editor.value) return false
  return tool.active?.() ?? false
}

function isDisabled(tool: ToolDef): boolean {
  void tick.value
  if (!editor.value) return true
  return tool.disabled?.() ?? false
}
</script>

<template>
  <section class="editor">
    <p v-if="loading" class="empty">正在打开…</p>

    <template v-else>
      <header class="editor-bar">
        <p class="bar-status">
          <span class="bar-dot" :class="{ 'is-dirty': dirty, 'is-saving': saving || uploading }" aria-hidden="true" />
          <span v-if="saving && metaWorking">生成摘要标签中…</span>
          <span v-else-if="saving">保存中…</span>
          <span v-else-if="uploading">正在上传图片…</span>
          <span v-else-if="dirty">有未保存的修改</span>
          <span v-else-if="!isNew">已与服务器同步</span>
          <span v-else>新笔记</span>
          <span class="bar-count"> · {{ wordCount }} 字</span>
        </p>
        <div class="bar-actions">
          <div class="mode-switch" role="tablist" aria-label="编辑模式">
            <button
              class="mode-btn"
              type="button"
              role="tab"
              :aria-selected="mode === 'rich'"
              :class="{ 'is-active': mode === 'rich' }"
              @click="mode = 'rich'"
            >
              富文本
            </button>
            <button
              class="mode-btn"
              type="button"
              role="tab"
              :aria-selected="mode === 'source'"
              :class="{ 'is-active': mode === 'source' }"
              @click="mode = 'source'"
            >
              Markdown 源码
            </button>
          </div>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickImage">
            {{ uploading ? '上传中' : '插入图片' }}
          </button>
          <button class="btn btn-text" type="button" @click="router.back()">取消</button>
          <button class="btn btn-primary btn-sm" type="button" :disabled="saving" @click="save">
            {{ saving ? (metaWorking ? '生成中' : '保存中') : '保存' }}
          </button>
        </div>
        <input
          ref="fileInput"
          class="visually-hidden"
          type="file"
          accept="image/png,image/jpeg,image/gif,image/webp"
          tabindex="-1"
          @change="onImagePicked"
        />
      </header>

      <div class="meta">
        <input v-model="title" class="field title-field" placeholder="标题" aria-label="标题" />
        <div class="meta-row">
          <input v-model="summary" class="field" placeholder="摘要（可选，会显示在博客列表）" aria-label="摘要" />
          <input v-model="tagsText" class="field" placeholder="标签，用逗号分隔" aria-label="标签" />
        </div>
      </div>

      <!-- ── 富文本模式 ── -->
      <div v-show="mode === 'rich'" class="rich-wrap">
        <div class="toolbar" role="toolbar" aria-label="格式工具栏">
          <template v-for="(tool, index) in richToolbar" :key="tool.title">
            <span v-if="index === 2 || index === 9 || index === 14 || index === 17" class="toolbar-sep" />
            <button
              class="tool"
              :class="{ 'is-active': isActive(tool) }"
              type="button"
              :title="tool.title"
              :aria-label="tool.title"
              :disabled="isDisabled(tool)"
              @click="tool.run()"
            >
              {{ tool.label }}
            </button>
          </template>
        </div>

        <div
          class="frame rich"
          :class="{ 'is-dragging': dragging }"
          @dragover="onDragOver"
          @dragleave="onDragLeave"
          @drop="onDrop"
        >
          <EditorContent :editor="editor" class="rich-body" />
          <div v-if="dragging" class="drop-hint"><span>松开即插入图片</span></div>
        </div>
        <p class="mode-hint">
          支持 Markdown 快捷输入：「## 」标题、「**加粗**」、「- 」列表、「> 」引用、「```」代码块、「- [ ] 」任务清单
        </p>
      </div>

      <!-- ── 源码模式（双栏） ── -->
      <div v-show="mode === 'source'" class="source-wrap">
        <div class="toolbar" role="toolbar" aria-label="Markdown 快捷操作">
          <button
            v-for="item in sourceToolbar"
            :key="item.title"
            class="tool"
            type="button"
            :title="item.title"
            :aria-label="item.title"
            @click="item.run()"
          >
            {{ item.label }}
          </button>
          <span class="toolbar-gap" />
          <label class="tool-toggle">
            <input v-model="syncScroll" type="checkbox" />
            跟随滚动
          </label>
        </div>

        <div
          class="panes"
          :class="{ 'is-dragging': dragging }"
          @dragover="onDragOver"
          @dragleave="onDragLeave"
          @drop="onDrop"
        >
          <div class="pane">
            <p class="pane-label">Markdown</p>
            <div class="frame source">
              <textarea
                ref="bodyEl"
                v-model="contentMd"
                spellcheck="false"
                aria-label="正文"
                @paste="onPaste"
                @scroll="onBodyScroll"
              />
            </div>
          </div>
          <div class="pane">
            <p class="pane-label">预览</p>
            <div ref="previewEl" class="frame preview">
              <article class="prose" v-html="preview" />
            </div>
          </div>
          <div v-if="dragging" class="drop-hint">
            <span>松开即插入图片</span>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.editor {
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--space-5) var(--space-5) var(--space-6);
}

.editor-bar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--hairline);
  flex-wrap: wrap;
}

.bar-status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-2);
}

/* 保存状态用一个小点表示：绿=已同步，黄=有改动，脉动=正在写 */
.bar-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
  transition: background-color var(--dur-2);
}

.bar-dot.is-dirty {
  background: var(--code-number);
}

.bar-dot.is-saving {
  background: var(--pen);
  animation: beat 1s var(--ease-soft) infinite;
}

@keyframes beat {
  50% {
    opacity: 0.3;
  }
}

.bar-count {
  color: var(--ink-3);
}

.bar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

/* 模式切换（语雀式的「富文本 / 源码」双页签） */
.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--hairline);
  border-radius: 8px;
  background: var(--surface);
}

.mode-btn {
  padding: 3px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-3);
  font-size: var(--text-xs);
  cursor: pointer;
  transition:
    color var(--dur-1),
    background-color var(--dur-1);
}

.mode-btn.is-active {
  background: var(--pen);
  color: var(--paper);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.meta {
  padding: var(--space-5) 0 var(--space-4);
}

.title-field {
  font-family: var(--font-serif);
  font-size: var(--text-h2);
  padding: var(--space-2) 0;
}

.meta-row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--space-5);
  margin-top: var(--space-3);
}

.meta-row .field {
  font-size: var(--text-sm);
}

/* ── 工具栏（两模式共用样式） ── */
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  padding: 6px 0 var(--space-3);
  border-top: 1px solid var(--hairline);
}

.tool {
  min-width: 30px;
  height: 26px;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition:
    color var(--dur-1),
    background-color var(--dur-1),
    border-color var(--dur-1);
}

.tool:hover:not(:disabled) {
  color: var(--pen);
  background: var(--row-hover);
  border-color: var(--hairline);
}

.tool:active:not(:disabled) {
  transform: translateY(1px);
}

.tool.is-active {
  color: var(--pen);
  background: var(--pen-soft);
  border-color: var(--pen);
}

.tool:disabled {
  opacity: 0.35;
  cursor: default;
}

.toolbar-sep {
  width: 1px;
  height: 16px;
  background: var(--hairline);
  margin: 0 4px;
}

.toolbar-gap {
  flex: 1;
}

.tool-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--ink-3);
  cursor: pointer;
  user-select: none;
}

.tool-toggle input {
  width: 13px;
  height: 13px;
  accent-color: var(--pen);
  cursor: pointer;
}

.mode-hint {
  margin: var(--space-2) 0 0;
  font-size: var(--text-xs);
  color: var(--ink-3);
}

/* ── 富文本编辑区 ── */
.rich-wrap {
  min-width: 0;
}

.frame {
  height: calc(100vh - 344px);
  min-height: 420px;
  overflow: hidden;
}

.frame.rich {
  overflow: auto;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--surface);
  padding: var(--space-4) var(--space-5);
}

.frame.rich.is-dragging {
  border-color: var(--pen);
  border-style: dashed;
  background: var(--pen-soft);
}

.drop-hint {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  border-radius: var(--radius);
  background: var(--pen-soft);
  color: var(--pen-ink);
  font-size: var(--text-sm);
  pointer-events: none;
}

.frame.rich .drop-hint {
  position: sticky;
}

/* Tiptap 编辑面：借 .prose 的排版，再补编辑器特有的状态 */
.frame.rich :deep(.tiptap) {
  outline: none;
  min-height: 380px;
  max-width: none;
}

.frame.rich :deep(.tiptap p.is-editor-empty:first-child)::before {
  content: attr(data-placeholder);
  color: var(--ink-3);
  pointer-events: none;
  float: left;
  height: 0;
}

/* 表格在编辑态要有可点的边框（阅读态由 .prose 管） */
.frame.rich :deep(.tiptap table) {
  border-collapse: collapse;
  width: 100%;
}

.frame.rich :deep(.tiptap th),
.frame.rich :deep(.tiptap td) {
  border: 1px solid var(--hairline);
  padding: 4px 8px;
  position: relative;
}

.frame.rich :deep(.tiptap th) {
  background: var(--pen-soft);
  text-align: left;
}

.frame.rich :deep(.tiptap .selectedCell::after) {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--pen-soft);
  pointer-events: none;
}

/* 任务列表 */
.frame.rich :deep(.tiptap ul[data-type='taskList']) {
  list-style: none;
  padding-left: 0;
}

.frame.rich :deep(.tiptap ul[data-type='taskList'] li) {
  display: flex;
  gap: var(--space-2);
}

.frame.rich :deep(.tiptap ul[data-type='taskList'] li > label) {
  flex-shrink: 0;
  margin-top: 5px;
}

.frame.rich :deep(.tiptap ul[data-type='taskList'] input[type='checkbox']) {
  accent-color: var(--pen);
}

/* 代码块编辑态底色（阅读态的高亮由 prose 管） */
.frame.rich :deep(.tiptap pre) {
  background: var(--code-bg, rgba(0, 0, 0, 0.04));
  border-radius: 8px;
  padding: var(--space-3) var(--space-4);
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
}

/* ── 源码模式双栏 ── */
.panes {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-5);
}

.pane {
  min-width: 0;
}

/* 拖拽时整块区域亮起来，否则用户不知道"能不能松手" */
.panes.is-dragging .frame {
  border-color: var(--pen);
  border-style: dashed;
  background: var(--pen-soft);
}

.panes .drop-hint {
  position: absolute;
}

.pane-label {
  font-size: var(--text-xs);
  color: var(--ink-3);
  margin-bottom: var(--space-2);
}

.source textarea {
  display: block;
  width: 100%;
  height: 100%;
  padding: var(--space-4);
  font-family: var(--font-mono);
  font-size: 14px;
  line-height: 1.7;
}

.preview {
  padding: var(--space-4) var(--space-5);
  overflow: auto;
}

.preview .prose {
  max-width: none;
}

@media (max-width: 960px) {
  .panes {
    grid-template-columns: 1fr;
  }

  .frame {
    height: auto;
    min-height: 360px;
  }

  .meta-row {
    grid-template-columns: 1fr;
  }
}
</style>
