<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { SearchResult } from '../api/notes'
import { renderMarkdown } from '../lib/markdown'
import {
  ask,
  busy,
  clearConversation,
  closeDrawer,
  copyText,
  examples,
  html,
  isOpen,
  messages,
  question,
  quotaLeft,
  refreshQuota,
  snippet,
  stage,
  streamingId,
  stop,
} from '../lib/askState'
import { isLoggedIn } from '../api/auth'

const router = useRouter()
const inputEl = ref<HTMLTextAreaElement>()
const listEl = ref<HTMLElement>()

/*
 * 抽屉宽度拖拽：把手在抽屉左缘，按住左右拖动实时改根元素上的 --ask-drawer-w。
 * 这个变量同时驱动抽屉本体和主内容的「推挤」padding（见 style.css），
 * 改一处两边自然同步。宽度记 localStorage，双击把手恢复默认。
 */
const WIDTH_KEY = 'ask-drawer-w-px'
const WIDTH_MIN = 360
const WIDTH_MAX = 960

function clampWidth(px: number): number {
  const max = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, window.innerWidth - 360))
  return Math.round(Math.min(Math.max(px, WIDTH_MIN), max))
}

function applyWidth(px: number): number {
  const w = clampWidth(px)
  document.documentElement.style.setProperty('--ask-drawer-w', `${w}px`)
  return w
}

function onResizeMove(e: PointerEvent) {
  applyWidth(window.innerWidth - e.clientX)
}

function onResizeUp(e: PointerEvent) {
  document.body.classList.remove('drawer-resizing')
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', onResizeUp)
  localStorage.setItem(WIDTH_KEY, String(applyWidth(window.innerWidth - e.clientX)))
}

function onResizeDown(e: PointerEvent) {
  if (window.innerWidth < 768) return
  e.preventDefault()
  document.body.classList.add('drawer-resizing')
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', onResizeUp)
}

function resetWidth() {
  localStorage.removeItem(WIDTH_KEY)
  document.documentElement.style.removeProperty('--ask-drawer-w')
}

/* ───────── 输入框自适应高度 ───────── */
function autoGrow() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

watch(question, () => void nextTick(autoGrow))

/* ───────── 滚动跟随：贴底才自动跟随，往上翻了就出「回到最新」按钮 ───────── */
const pinned = ref(true)

function onScroll() {
  const el = listEl.value
  if (!el) return
  pinned.value = el.scrollHeight - el.scrollTop - el.clientHeight < 64
}

function scrollBottom(smooth = false) {
  const el = listEl.value
  if (!el) return
  el.scrollTo({
    top: el.scrollHeight,
    behavior: smooth && !window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'smooth' : 'auto',
  })
}

watch(
  () => [messages.value.length, stage.value],
  async () => {
    await nextTick()
    if (pinned.value) scrollBottom()
  },
)

// 流式期间内容逐帧变化，贴底状态下一路跟随
watch(html, async () => {
  if (!pinned.value) return
  await nextTick()
  scrollBottom()
})

watch(isOpen, async (open) => {
  if (open) {
    await nextTick()
    autoGrow()
    scrollBottom()
    inputEl.value?.focus()
  }
})

function onKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void ask()
  }
}

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && isOpen.value) {
    closeDrawer()
  }
}

function selectSource(source: SearchResult) {
  // 窄屏（手机端）点出处时收起抽屉让出主视口；宽屏保持开启，便于同屏对照
  if (window.innerWidth < 768) {
    closeDrawer()
  }
  // 游客只能看公开文章：检索结果本来就只含已发布内容，
  // 跳到 /notes/:slug（需登录）会把人弹去登录页，应走公开的 /blog/:slug
  void router.push({
    name: isLoggedIn() ? 'note-detail' : 'blog-detail',
    params: { slug: source.slug },
    query: source.heading ? { heading: source.heading } : {},
  })
}

const sendDisabled = computed(() => busy.value || !question.value.trim())

/** 游客（未登录）：显示剩余次数、提示限次，且检索范围只有已发布文章 */
const isGuest = computed(() => !isLoggedIn())

// 抽屉打开时刷新一次剩余：用户可能在别的标签页用掉了今天的额度
watch(isOpen, (open) => {
  if (open) refreshQuota()
})

onMounted(() => {
  const saved = Number(localStorage.getItem(WIDTH_KEY))
  if (saved >= WIDTH_MIN) applyWidth(saved)
  refreshQuota()
  window.addEventListener('keydown', onWindowKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  document.body.classList.remove('drawer-resizing')
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', onResizeUp)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="isOpen" class="ask-drawer-scrim" @click.self="closeDrawer">
        <aside class="ask-drawer" aria-label="问你的笔记">
          <!-- 左缘宽度拖拽把手（宽屏才有；双击恢复默认宽度） -->
          <div
            class="drawer-resize-handle"
            title="拖动调节宽度 · 双击恢复默认"
            @pointerdown="onResizeDown"
            @dblclick="resetWidth"
          />

          <!-- 抽屉顶栏 -->
          <header class="drawer-header">
            <div class="drawer-title-group">
              <span class="drawer-badge" aria-hidden="true">✦</span>
              <div>
                <h2 class="drawer-title">{{ isGuest ? '问这座博客' : '问你的笔记' }}</h2>
                <!-- 文案随身份变：游客只能检索已发布文章，写「全库」是错的 -->
                <p class="drawer-subtitle">
                  {{ isGuest ? '检索已发布文章 · 流式作答 · 附出处' : '检索全库笔记 · 流式作答 · 附出处' }}
                </p>
              </div>
            </div>
            <div class="header-actions">
              <button
                v-if="messages.length"
                class="btn-icon"
                type="button"
                aria-label="清空对话"
                title="清空对话"
                @click="clearConversation"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 10v7M14 10v7" />
                </svg>
              </button>
              <button
                class="btn-icon"
                type="button"
                aria-label="关闭问答抽屉 (Esc)"
                title="关闭 (Esc)"
                @click="closeDrawer"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          </header>

          <!-- 对话流 -->
          <div ref="listEl" class="msg-list" @scroll.passive="onScroll">
            <!-- 空态 -->
            <div v-if="!messages.length" class="empty-state">
              <span class="empty-badge" aria-hidden="true">✦</span>
              <h3 class="empty-title">想问点什么？</h3>
              <p class="empty-sub">向量检索 + 关键词融合，答案都标着笔记出处</p>
              <div class="example-list">
                <button
                  v-for="example in examples"
                  :key="example"
                  class="example-chip"
                  type="button"
                  :disabled="busy"
                  @click="ask(example)"
                >
                  {{ example }}
                </button>
              </div>
            </div>

            <TransitionGroup v-else name="msg" tag="div" class="msg-flow">
              <div v-for="m in messages" :key="m.id" class="msg" :class="`msg-${m.role}`">
                <!-- 用户消息：右对齐气泡 -->
                <div v-if="m.role === 'user'" class="user-bubble">{{ m.text }}</div>

                <!-- 助手消息：通栏 markdown + 出处折叠 -->
                <div v-else class="assistant-msg">
                  <div v-if="m.error" class="error-tip">{{ m.error }}</div>
                  <template v-else>
                    <!-- 正在生成的这条走合帧 HTML；历史消息直接渲染 -->
                    <div v-if="m.id === streamingId" class="prose streaming" v-html="html" />
                    <div v-else class="prose" v-html="renderMarkdown(m.text)" />

                    <div class="msg-foot">
                      <span v-if="m.id === streamingId" class="status-note live"> 正在作答<span class="caret" /> </span>
                      <span v-else-if="m.stopped && !m.text" class="status-note">已停止生成</span>
                      <template v-else-if="m.text">
                        <button class="foot-btn" type="button" @click="copyText(m.text)">复制</button>
                        <span v-if="m.stopped" class="status-note">已停止生成</span>
                      </template>
                    </div>

                    <details v-if="m.sources?.length" class="sources">
                      <summary>
                        <span class="summary-label">引用笔记 · {{ m.sources.length }}</span>
                        <span class="summary-chevron" aria-hidden="true">▾</span>
                      </summary>
                      <div class="source-items">
                        <button
                          v-for="(source, index) in m.sources"
                          :key="index"
                          class="source-item"
                          type="button"
                          @click="selectSource(source)"
                        >
                          <span class="source-index">{{ String(index + 1).padStart(2, '0') }}</span>
                          <span class="source-body">
                            <span class="source-title-line">
                              <span class="source-title">{{ source.title }}</span>
                              <span v-if="source.heading" class="source-section">§ {{ source.heading }}</span>
                            </span>
                            <span class="source-snippet">{{ snippet(source.content) }}</span>
                          </span>
                        </button>
                      </div>
                    </details>
                  </template>
                </div>
              </div>
            </TransitionGroup>

            <!-- 检索阶段提示（还没到出字的阶段） -->
            <Transition name="stage">
              <div v-if="stage === 'searching'" class="stage-tip">
                <span class="pulse" aria-hidden="true"><i /><i /><i /></span>
                正在检索相关笔记章节
              </div>
            </Transition>
          </div>

          <!-- 回到最新 -->
          <Transition name="jump">
            <button v-if="!pinned && messages.length" class="jump-latest" type="button" @click="scrollBottom(true)">
              ↓ 回到最新
            </button>
          </Transition>

          <!-- 底部输入区 -->
          <form class="composer" @submit.prevent="ask()">
            <textarea
              ref="inputEl"
              v-model="question"
              class="composer-input"
              rows="1"
              :placeholder="
                isGuest ? '向已发布文章提问…（Enter 发送）' : '向全库笔记提问…（Enter 发送，Shift+Enter 换行）'
              "
              @keydown="onKey"
            />
            <div class="composer-bar">
              <span class="composer-hint">
                <template v-if="isGuest && quotaLeft >= 0"> 今日还可提问 {{ quotaLeft }} 次 </template>
                <template v-else>依据笔记内容作答，可能出错，请核对出处</template>
              </span>
              <button
                v-if="busy"
                class="composer-btn stop"
                type="button"
                aria-label="停止生成"
                title="停止生成"
                @click="stop"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1.5" /></svg>
              </button>
              <button
                v-else
                class="composer-btn send"
                type="submit"
                aria-label="发送"
                title="发送 (Enter)"
                :disabled="sendDisabled"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 19V5M5 12l7-7 7 7" />
                </svg>
              </button>
            </div>
          </form>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/*
 * 抽屉容器：桌面端是个"透明壳"——不铺遮罩、不接点击（pointer-events: none），
 * 左边的笔记列表照常可看可点；只有抽屉本体（pointer-events: auto）接管右侧一条。
 * 窄屏（<768px）屏幕放不下两栏，恢复成传统的遮罩 + 点击空白关闭。
 */
.ask-drawer-scrim {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  justify-content: flex-end;
  pointer-events: none;
}

.ask-drawer {
  position: relative;
  width: var(--ask-drawer-w);
  height: 100vh;
  height: 100dvh;
  background: var(--paper);
  border-left: 1px solid var(--hairline);
  box-shadow: var(--lift-3);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
}

/* 左缘宽度拖拽把手：比视觉条宽一倍的命中区，好抓 */
.drawer-resize-handle {
  position: absolute;
  top: 0;
  left: -4px;
  width: 9px;
  height: 100%;
  cursor: col-resize;
  z-index: 5;
  touch-action: none;
}

.drawer-resize-handle::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 3px;
  width: 2px;
  background: transparent;
  transition: background-color var(--dur-1) var(--ease-soft);
}

.drawer-resize-handle:hover::after,
:global(body.drawer-resizing) .drawer-resize-handle::after {
  background: var(--pen);
  opacity: 0.4;
}

/* 抽屉划入动效 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.drawer-enter-active .ask-drawer,
.drawer-leave-active .ask-drawer {
  transition: transform var(--dur-3) var(--ease-out);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .ask-drawer,
.drawer-leave-to .ask-drawer {
  transform: translateX(100%);
}

/* ── 顶栏 ── */
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--hairline);
  background: var(--surface);
  flex-shrink: 0;
}

.drawer-title-group {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.drawer-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--pen-soft);
  color: var(--pen);
  font-size: var(--text-md);
}

.drawer-title {
  font-family: var(--font-serif);
  font-size: var(--text-lg);
  font-weight: 600;
  margin: 0;
  color: var(--ink);
}

.drawer-subtitle {
  font-size: var(--text-xs);
  color: var(--ink-3);
  margin: 1px 0 0 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: var(--ink-3);
  cursor: pointer;
  transition:
    background-color var(--dur-1) var(--ease-soft),
    color var(--dur-1) var(--ease-soft);
}

.btn-icon:hover {
  background: var(--row-hover);
  color: var(--ink);
}

.btn-icon svg {
  width: 17px;
  height: 17px;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  fill: none;
}

/* ── 对话流 ── */
.msg-list {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--space-5) var(--space-5) var(--space-3);
  display: flex;
  flex-direction: column;
}

.msg-flow {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* 消息进出动效 */
.msg-enter-active {
  transition:
    opacity var(--dur-2) var(--ease-out),
    transform var(--dur-2) var(--ease-out);
}

.msg-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.msg-user {
  display: flex;
  justify-content: flex-end;
}

.user-bubble {
  max-width: 85%;
  padding: var(--space-3) var(--space-4);
  background: var(--pen-soft);
  border: 1px solid var(--hairline);
  border-radius: 14px 14px 4px 14px;
  font-family: var(--font-serif);
  font-size: var(--text-md);
  line-height: 1.7;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.assistant-msg {
  min-width: 0;
}

.assistant-msg .prose {
  font-size: var(--text-md);
  line-height: 1.75;
}

/* 流式期间的行尾光标见 src/style.css 的 .prose.streaming 规则：
   组件 scoped 样式会给末尾类型选择器加 [data-v]，而 v-html 渲染的元素没有这个属性，
   光标就整个不显示了，所以放在全局样式里。 */

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
  min-height: 22px;
  font-size: var(--text-xs);
}

.foot-btn {
  padding: 1px 6px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--ink-3);
  font-size: var(--text-xs);
  cursor: pointer;
  transition:
    color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.foot-btn:hover {
  color: var(--pen);
  background: var(--pen-soft);
}

.status-note {
  color: var(--ink-3);
}

.status-note.live {
  color: var(--pen);
  font-weight: 500;
}

.caret {
  display: inline-block;
  width: 2px;
  height: 12px;
  margin-left: 4px;
  background: var(--pen);
  vertical-align: -1px;
  animation: blink 1s infinite steps(1);
}

/* ── 出处折叠 ── */
.sources {
  margin-top: var(--space-2);
  border-top: 1px dashed var(--hairline);
  padding-top: var(--space-2);
}

.sources summary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  cursor: pointer;
  list-style: none;
  font-size: var(--text-xs);
  color: var(--ink-3);
  user-select: none;
  transition: color var(--dur-1) var(--ease-soft);
}

.sources summary::-webkit-details-marker {
  display: none;
}

.sources summary:hover {
  color: var(--pen);
}

.summary-chevron {
  transition: transform var(--dur-1) var(--ease-soft);
}

.sources[open] .summary-chevron {
  transform: rotate(180deg);
}

.source-items {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-top: var(--space-2);
}

.source-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  font: inherit;
  transition:
    border-color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.source-item:hover {
  border-color: var(--pen);
  background: var(--row-hover);
}

.source-index {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--ink-3);
  padding-top: 2px;
}

.source-body {
  flex: 1;
  min-width: 0;
}

.source-title-line {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.source-title {
  font-weight: 600;
  font-size: var(--text-sm);
  color: var(--ink);
}

.source-section {
  font-size: var(--text-xs);
  color: var(--pen);
}

.source-snippet {
  display: block;
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--ink-2);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ── 空态 ── */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: var(--space-6) var(--space-4);
  gap: var(--space-2);
}

.empty-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 16px;
  background: var(--pen-soft);
  color: var(--pen);
  font-size: 22px;
  margin-bottom: var(--space-2);
  animation: halo 2.4s var(--ease-soft) infinite;
}

@keyframes halo {
  0% {
    box-shadow: 0 0 0 0 rgba(47, 93, 87, 0.25);
  }
  70%,
  100% {
    box-shadow: 0 0 0 14px rgba(47, 93, 87, 0);
  }
}

.empty-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
  font-weight: 600;
  color: var(--ink);
  margin: 0;
}

.empty-sub {
  font-size: var(--text-sm);
  color: var(--ink-3);
  margin: 0;
}

.example-list {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--space-2);
  margin-top: var(--space-4);
  width: 100%;
  max-width: 20rem;
}

.example-chip {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--hairline);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--text-sm);
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    color var(--dur-1) var(--ease-soft),
    border-color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.example-chip:hover:not(:disabled) {
  color: var(--pen);
  border-color: var(--pen);
  background: var(--pen-soft);
}

/* ── 检索阶段提示 ── */
.stage-tip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--pen);
  padding: var(--space-2) var(--space-3);
  background: var(--pen-soft);
  border-radius: 8px;
  width: fit-content;
  margin-top: var(--space-2);
}

.stage-enter-active,
.stage-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.stage-enter-from,
.stage-leave-to {
  opacity: 0;
}

.pulse {
  display: inline-flex;
  gap: 3px;
}

.pulse i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 1.2s infinite ease-in-out;
}

.pulse i:nth-child(2) {
  animation-delay: 0.2s;
}

.pulse i:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes pulse {
  0%,
  80%,
  100% {
    opacity: 0.3;
    transform: scale(0.8);
  }
  40% {
    opacity: 1;
    transform: scale(1.1);
  }
}

.error-tip {
  font-size: var(--text-sm);
  color: var(--danger);
  padding: var(--space-2) var(--space-3);
  background: rgba(164, 67, 58, 0.08);
  border-radius: 8px;
}

/* ── 回到最新 ── */
.jump-latest {
  position: absolute;
  bottom: 132px;
  right: var(--space-5);
  z-index: 4;
  padding: 5px 12px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--text-xs);
  cursor: pointer;
  box-shadow: var(--lift-2);
  transition:
    color var(--dur-1) var(--ease-soft),
    border-color var(--dur-1) var(--ease-soft);
}

.jump-latest:hover {
  color: var(--pen);
  border-color: var(--pen);
}

.jump-enter-active,
.jump-leave-active {
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-2) var(--ease-soft);
}

.jump-enter-from,
.jump-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* ── 底部输入区 ── */
.composer {
  flex-shrink: 0;
  margin: var(--space-3) var(--space-4) var(--space-4);
  border: 1px solid var(--hairline);
  border-radius: 14px;
  background: var(--surface);
  box-shadow: var(--lift-1);
  transition: border-color var(--dur-1) var(--ease-soft);
}

.composer:focus-within {
  border-color: var(--pen);
}

.composer-input {
  display: block;
  width: 100%;
  border: none;
  background: transparent;
  resize: none;
  padding: var(--space-3) var(--space-4) var(--space-1);
  font-family: var(--font-serif);
  font-size: var(--text-md);
  line-height: 1.6;
  color: var(--ink);
  max-height: 160px;
}

.composer-input:focus {
  outline: none;
}

.composer-input::placeholder {
  color: var(--ink-3);
  font-size: var(--text-sm);
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-1) var(--space-2) var(--space-2) var(--space-4);
}

.composer-hint {
  font-size: 11px;
  color: var(--ink-3);
}

.composer-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  transition:
    opacity var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft),
    transform var(--dur-1) var(--ease-soft);
}

.composer-btn svg {
  width: 17px;
  height: 17px;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  fill: none;
}

.composer-btn.send {
  background: var(--pen);
  color: var(--paper);
}

.composer-btn.send:hover:not(:disabled) {
  transform: translateY(-1px);
}

.composer-btn.send:disabled {
  opacity: 0.35;
  cursor: default;
}

.composer-btn.stop {
  background: var(--danger);
  color: var(--paper);
}

/* ── 窄屏：全屏遮罩模式 ── */
@media (max-width: 767px) {
  .drawer-resize-handle {
    display: none;
  }

  .ask-drawer-scrim {
    pointer-events: auto;
    background: var(--scrim);
    backdrop-filter: blur(2px);
  }

  .jump-latest {
    bottom: 120px;
    right: var(--space-4);
  }
}
</style>

<style>
/* 拖拽调宽期间的全局状态：禁止选中文字、关掉推挤过渡（否则 padding 过渡拖后腿）。
   这些选择器落在组件外的 body/.main/.topbar 上，必须放在非 scoped 块里。 */
body.drawer-resizing {
  cursor: col-resize;
  user-select: none;
  -webkit-user-select: none;
}

body.drawer-resizing .main,
body.drawer-resizing .topbar {
  transition: none !important;
}
</style>
