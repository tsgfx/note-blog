import { Editor } from '@tiptap/vue-3'
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

export interface RichMarkdownEditorOptions {
  initialMarkdown: string
  onImageFile: (file: File) => void
  onUpdate: () => void
  onTransaction: () => void
  onCreate: () => void
}

/* Markdown 快捷输入增强（中文友好）。
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

function createTaskListShortcut(getEditor: () => Editor | undefined) {
  return Extension.create({
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
              const editor = getEditor()
              if (!editor) return
              // 已在任务清单里（比如连着输多个待办）不能再 toggle，否则整个清单被翻回去
              const { $from } = editor.state.selection
              let inTaskList = false
              for (let d = $from.depth; d > 0; d--) {
                if ($from.node(d).type.name === 'taskList') {
                  inTaskList = true
                  break
                }
              }
              if (inTaskList) {
                editor.commands.updateAttributes('taskItem', { checked })
              } else {
                editor.chain().toggleTaskList().updateAttributes('taskItem', { checked }).run()
              }
            })
          },
        }),
      ]
    },
  })
}

const LenientCodeBlock = CodeBlock.extend({
  addInputRules() {
    return [
      // 官方规则要求 ``` 后必须跟空格才触发；放宽成敲完 ``` 直接转，
      // 顺手把 ```python 这种手打的简写记成代码语言
      textblockTypeInputRule({
        find: /^```([a-zA-Z0-9+#-]+)?$/,
        type: this.type,
        getAttributes: (match) => ({ language: match[1]?.toLowerCase() || null }),
      }),
    ]
  },
})

/** 创建笔记富文本编辑器。扩展顺序、Markdown 输入规则和图片粘贴/拖拽都封装在这里。 */
export function createRichMarkdownEditor(options: RichMarkdownEditorOptions): Editor {
  const editorRef: { current?: Editor } = {}
  const taskListShortcut = createTaskListShortcut(() => editorRef.current)
  const instance = new Editor({
    content: options.initialMarkdown,
    extensions: [
      // 列表辅助规则必须排在 StarterKit 之前，否则官方 bulletList 规则先生效。
      // Tiptap 对重复扩展只警告不去重；这里只保留这一个有效位置。
      taskListShortcut,
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
        options.onImageFile(file)
        return true
      },
      handleDrop: (_view, event, _slice, moved) => {
        const file = [...(event.dataTransfer?.files ?? [])].find((item) => item.type.startsWith('image/'))
        if (!file || moved) return false
        event.preventDefault()
        options.onImageFile(file)
        return true
      },
    },
    onUpdate: options.onUpdate,
    onTransaction: options.onTransaction,
    onCreate: options.onCreate,
    autofocus: 'end',
  })
  editorRef.current = instance
  instance.on('transaction', options.onTransaction)
  return instance
}
