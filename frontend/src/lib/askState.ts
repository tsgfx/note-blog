import { computed, ref } from 'vue'
import type { SearchResult } from '../api/notes'
import { renderMarkdown } from './markdown'
import { notify } from './feedback'
import { canAsk, consumeLocally, remaining, syncFromServer } from './guestQuota'

export type AskStage = 'idle' | 'searching' | 'answering'

/** 一条对话消息。assistant 消息在流式期间 text 仍为空，内容走 renderSrc/html */
export interface AskMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  sources?: SearchResult[]
  stopped?: boolean
  error?: string
}

export const examples = ['GFS_VL 的环境怎么配置？', 'Spring 事务是怎么管理的？', 'Docker 部署踩过哪些坑？']

export const isOpen = ref(false)
/** 输入框内容（组件 v-model 用） */
export const question = ref('')
/** 完整对话历史，按时间正序 */
export const messages = ref<AskMessage[]>([])
export const stage = ref<AskStage>('idle')
/** 正在流式生成的 assistant 消息 id；null 表示当前没有生成中的消息 */
export const streamingId = ref<number | null>(null)

export const busy = computed(() => stage.value === 'searching' || stage.value === 'answering')

let idSeq = 0
let controller: AbortController | null = null
let currentMsg: AskMessage | null = null

/* ───────── 流式渲染的 rAF 合帧 ─────────
   每个 SSE delta 都直接 renderMarkdown 会把主线程打满：
   delta 先攒进普通变量 streamBuf，每帧只把攒好的全文落进 renderSrc 一次，
   markdown 重渲染随之合帧。历史消息内容不变，直接用 renderMarkdown(text)。 */
let renderFrame = 0
let streamBuf = ''
const renderSrc = ref('')

function scheduleRender() {
  if (renderFrame) return
  renderFrame = window.requestAnimationFrame(() => {
    renderFrame = 0
    renderSrc.value = streamBuf
  })
}

/** 把缓冲落进消息本体，结束一条 assistant 消息的流式状态 */
function finishMessage() {
  if (renderFrame) {
    window.cancelAnimationFrame(renderFrame)
    renderFrame = 0
  }
  if (currentMsg) {
    currentMsg.text = streamBuf
  }
  streamBuf = ''
  renderSrc.value = ''
  streamingId.value = null
  currentMsg = null
  stage.value = 'idle'
}

/** 正在生成的消息内容（rAF 合帧后的 markdown HTML） */
export const html = computed(() => renderMarkdown(renderSrc.value))

interface StreamEvent {
  type: string
  data: Record<string, unknown>
}

function parseEvent(block: string): StreamEvent | null {
  const line = block.split('\n').find((row) => row.startsWith('data:'))
  if (!line) return null
  try {
    const payload = JSON.parse(line.slice(5).trim()) as StreamEvent
    return { type: payload.type, data: payload.data ?? {} }
  } catch {
    return null
  }
}

/** 游客今日剩余提问次数（响应式，供界面显示）。
 *  声明在 handle() 之前：handle 会写它，而 const 有暂时性死区。 */
export const quotaLeft = ref(remaining())

/** 同步一次剩余（打开抽屉、收到服务端数据后调用） */
export function refreshQuota() {
  quotaLeft.value = remaining()
}

function handle(event: StreamEvent | null) {
  if (!event || !currentMsg) return
  if (event.type === 'sources') {
    currentMsg.sources = (event.data.sources as SearchResult[]) ?? []
  } else if (event.type === 'quota') {
    // 服务端带回来的权威剩余次数：覆盖本地，避免两边漂移
    syncFromServer(Number(event.data.remaining))
    quotaLeft.value = remaining()
  } else if (event.type === 'delta') {
    streamBuf += String(event.data.text ?? '')
    scheduleRender()
  } else if (event.type === 'error') {
    currentMsg.error = String(event.data.message ?? '问答失败')
    finishMessage()
  } else if (event.type === 'done') {
    finishMessage()
  }
}

export async function ask(text = question.value) {
  const q = text.trim()
  if (!q || busy.value) return

  // 游客本地配额：提前拦住并说明原因，别等发出去才被服务端拒。
  // 这不是安全措施（清缓存就能绕过），服务端按 IP 仍有硬上限。
  const guest = !localStorage.getItem('notes_blog_token')
  if (guest && !canAsk()) {
    messages.value.push({ id: ++idSeq, role: 'user', text: q })
    messages.value.push({
      id: ++idSeq,
      role: 'assistant',
      text: '',
      error: '今天的提问次数已经用完了，明天再来。',
    })
    isOpen.value = true
    return
  }

  question.value = ''
  controller?.abort()
  controller = new AbortController()
  error.value = ''

  messages.value.push({ id: ++idSeq, role: 'user', text: q })
  const msg: AskMessage = { id: ++idSeq, role: 'assistant', text: '' }
  messages.value.push(msg)
  currentMsg = msg
  streamBuf = ''
  renderSrc.value = ''
  streamingId.value = msg.id
  stage.value = 'searching'
  isOpen.value = true

  try {
    const token = localStorage.getItem('notes_blog_token')
    const response = await fetch('/api/ask', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ question: q }),
    })

    // 429：配额用完。服务端这时还没开流，回的是普通 JSON。
    // 必须单独处理——当成「生成失败」会让用户以为服务挂了。
    if (response.status === 429) {
      let message = '今天的提问次数已经用完了，明天再来。'
      let resetSeconds = 0
      try {
        const body = (await response.json()) as {
          message?: string
          data?: { resetInSeconds?: number }
        }
        if (body.message) message = body.message
        resetSeconds = Number(body.data?.resetInSeconds) || 0
      } catch {
        // JSON 解不出来就用默认文案，不影响主流程
      }
      syncFromServer(0)
      quotaLeft.value = 0
      msg.error = resetSeconds > 0 ? `${message}（约 ${Math.ceil(resetSeconds / 3600)} 小时后重置）` : message
      finishMessage()
      return
    }
    if (response.status === 401) throw new Error('登录已过期，请重新登录')
    if (!response.ok || !response.body) throw new Error(`问答服务返回 ${response.status}`)

    // 请求已被服务端接受，记一次本地用量
    if (guest) {
      consumeLocally()
      quotaLeft.value = remaining()
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() ?? ''
      blocks.forEach((block) => handle(parseEvent(block)))
    }
    if (buffer) handle(parseEvent(buffer))
    // 流正常结束但服务端没发 done（连接被掐断等）：把已有内容落袋为安
    if (streamingId.value === msg.id) finishMessage()
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
    if (currentMsg) {
      currentMsg.error = e instanceof Error ? e.message : String(e)
      finishMessage()
    }
  }
}

/** 请求级错误（进不了流的那种），由 catch 写入消息 error 字段后残留于此供调试 */
export const error = ref('')

export function stop() {
  if (!busy.value) return
  controller?.abort()
  if (currentMsg) {
    currentMsg.stopped = true
    currentMsg.text = streamBuf
  }
  finishMessage()
}

export async function copyText(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    notify('已复制')
  } catch {
    notify('复制失败，请手动选择文本', 'error')
  }
}

/** 清空对话，回到空态 */
export function clearConversation() {
  controller?.abort()
  messages.value = []
  question.value = ''
  renderSrc.value = ''
  streamingId.value = null
  currentMsg = null
  stage.value = 'idle'
}

export function openDrawer(initialQuestion?: string, autoAsk = false) {
  isOpen.value = true
  if (initialQuestion !== undefined && initialQuestion.trim()) {
    if (autoAsk) {
      void ask(initialQuestion.trim())
    } else {
      question.value = initialQuestion.trim()
    }
  }
}

export function closeDrawer() {
  isOpen.value = false
}

export function toggleDrawer() {
  isOpen.value = !isOpen.value
}

/** 把 Markdown 片段压成一行纯文本，供出处预览使用。 */
export function snippet(content: string): string {
  return content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\s\\(\s|$)/g, ' ')
    .replace(/[#*_`>|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 140)
}
