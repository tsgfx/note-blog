<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  generateNoteMeta,
  getNote,
  getPublishedNote,
  getRelatedNotes,
  publishNote,
  unpublishNote,
  type NoteDetail,
  type RelatedNote,
} from '../api/notes'
import { isLoggedIn } from '../api/auth'
import { buildOutline, renderMarkdown, type OutlineItem } from '../lib/markdown'
import { notify } from '../lib/feedback'
import { openDrawer } from '../lib/askState'

const route = useRoute()
const note = ref<NoteDetail | null>(null)
const error = ref('')
const loading = ref(true)
const article = ref<HTMLElement>()
const outline = ref<OutlineItem[]>([])
const activeId = ref('')
const progress = ref(0)
const showTop = ref(false)
const lightbox = ref('')
const related = ref<RelatedNote[]>([])
const isPublicRoute = computed(() => route.name === 'blog-detail')
const canEdit = computed(() => !isPublicRoute.value && isLoggedIn())
const html = computed(() => (note.value ? renderMarkdown(note.value.contentMd) : ''))
const folder = computed(() => {
  const source = note.value?.sourcePath
  if (!source) return '新建笔记'
  return source.split('/').slice(0, -1).join(' / ')
})
const date = computed(() => (note.value?.updatedAt ?? '').slice(0, 10))
const republishing = ref(false)

/** 中文按 400 字/分钟估算，仅用于让读者心里有个数 */
const charCount = computed(() => (note.value?.contentMd ?? '').replace(/\s+/g, '').length)
const readMinutes = computed(() => Math.max(1, Math.round(charCount.value / 400)))

async function load() {
  loading.value = true
  error.value = ''
  related.value = []
  try {
    const slug = String(route.params.slug)
    note.value = isPublicRoute.value ? await getPublishedNote(slug) : await getNote(slug)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    note.value = null
  } finally {
    loading.value = false
  }
  await nextTick()
  if (article.value) {
    outline.value = buildOutline(article.value)
    locateFromQuery()
    syncActive()
  }
  loadRelated()
}

/**
 * 相关笔记：只在登录后的笔记页拉（公开博客页是匿名请求，该接口需要鉴权）。
 * 失败静默——推荐是锦上添花，不该让读者看到报错。
 */
async function loadRelated() {
  if (!note.value || !canEdit.value) return
  try {
    related.value = await getRelatedNotes(note.value.id)
  } catch {
    related.value = []
  }
}

function locateFromQuery() {
  const target = String(route.query.heading ?? '').trim()
  if (!target || !article.value) return
  const heading = [...article.value.querySelectorAll<HTMLElement>('h1, h2, h3, h4')].find(
    (el) => el.textContent?.trim() === target,
  )
  if (!heading) return
  heading.classList.add('located')
  heading.scrollIntoView({ block: 'start' })
  window.setTimeout(() => heading.classList.remove('located'), 2600)
}

function jump(item: OutlineItem) {
  document.getElementById(item.id)?.scrollIntoView({ block: 'start' })
  activeId.value = item.id
}

/**
 * 滚动时同步：顶部进度条、大纲当前项、回到顶部按钮。
 * 用 rAF 合并，避免每帧多次布局查询。
 */
let ticking = false

function onScroll() {
  if (ticking) return
  ticking = true
  window.requestAnimationFrame(() => {
    ticking = false
    const doc = document.documentElement
    const max = doc.scrollHeight - window.innerHeight
    progress.value = max > 8 ? Math.min(100, Math.max(0, (window.scrollY / max) * 100)) : 0
    showTop.value = window.scrollY > 600
    syncActive()
  })
}

/** 最后一个顶端越过顶栏的标题就是"当前章节" */
function syncActive() {
  const heads = article.value?.querySelectorAll<HTMLElement>('h1, h2, h3, h4')
  if (!heads?.length) return
  let current = ''
  heads.forEach((el) => {
    if (el.getBoundingClientRect().top <= 96) current = el.id
  })
  activeId.value = current || (heads[0]?.id ?? '')
}

function toTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 正文图片点开看大图。图片宽度被 --measure 限制，长图里的细节根本看不清 */
function onArticleClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  if (target.tagName === 'IMG') lightbox.value = (target as HTMLImageElement).src
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') lightbox.value = ''
}

async function togglePublish() {
  if (!note.value) return
  try {
    const updated =
      note.value.status === 'PUBLISHED' ? await unpublishNote(note.value.id) : await publishNote(note.value.id)
    note.value = { ...note.value, status: updated.status, hasUnpublishedEdits: updated.hasUnpublishedEdits }
    notify(updated.status === 'PUBLISHED' ? '已发布' : '已转为草稿')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  }
}

/**
 * 把改过的草稿重新推上线。
 *
 * <p>单独一个函数而不是复用 togglePublish：这里的状态是「已发布但内容旧」，
 * 点它应该是发布而不是下线——用切换按钮会正好点反。
 */
async function republish() {
  if (!note.value || republishing.value) return
  republishing.value = true
  try {
    const updated = await publishNote(note.value.id)
    note.value = { ...note.value, ...updated }
    notify('已重新发布，线上内容已更新', 'success')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    republishing.value = false
  }
}

/** 让 AI 重写摘要与标签（覆盖现有值），完成后原地刷新展示 */
const metaGenerating = ref(false)
async function regenerateMeta() {
  if (!note.value || metaGenerating.value) return
  metaGenerating.value = true
  try {
    const updated = await generateNoteMeta(note.value.id, true)
    note.value = { ...note.value, summary: updated.summary, tags: updated.tags }
    notify('摘要与标签已重新生成')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    metaGenerating.value = false
  }
}

watch(
  () => route.fullPath,
  (next, prev) => {
    if (next !== prev) load()
  },
)

onMounted(() => {
  load()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="reading">
    <div v-if="!loading && note" class="progress" aria-hidden="true">
      <span class="progress-fill" :style="{ transform: `scaleX(${progress / 100})` }" />
    </div>

    <section class="page reader">
      <p v-if="loading" class="empty">正在打开…</p>
      <p v-else-if="error" class="error-text">{{ error }}</p>

      <template v-else-if="note">
        <header class="reader-head">
          <p class="crumb muted">
            <RouterLink v-if="canEdit" to="/notes">笔记</RouterLink>
            <span v-if="canEdit"> / </span>
            {{ folder || '根目录' }}
          </p>
          <h1 class="reader-title">{{ note.title }}</h1>
          <div class="reader-meta">
            <span>{{ date }}</span>
            <span class="meta-sep" aria-hidden="true">·</span>
            <span>{{ charCount }} 字 · 约 {{ readMinutes }} 分钟</span>
            <span v-if="note.tags.length" class="meta-tags">
              <span v-for="tag in note.tags" :key="tag" class="meta-tag">{{ tag }}</span>
            </span>
            <span
              v-if="canEdit"
              class="state"
              :class="{
                'state-live': note.status === 'PUBLISHED' && !note.hasUnpublishedEdits,
                'state-pending': note.hasUnpublishedEdits,
              }"
            >
              {{ note.hasUnpublishedEdits ? '待重发' : note.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </span>
          </div>
          <div v-if="canEdit" class="reader-actions">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              title="打开 AI 问答助手 (Cmd+J / Ctrl+J)"
              @click="openDrawer()"
            >
              <span class="sparkle" aria-hidden="true">✦</span> 问答
            </button>
            <RouterLink class="btn btn-ghost btn-sm" :to="`/editor/${note.slug}`">编辑</RouterLink>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              title="让 AI 重新生成摘要与标签"
              :disabled="metaGenerating"
              @click="regenerateMeta"
            >
              {{ metaGenerating ? '生成中…' : '✨ 摘要标签' }}
            </button>
            <button class="btn btn-text" type="button" @click="togglePublish">
              {{ note.status === 'PUBLISHED' ? '下线' : '发布' }}
            </button>
          </div>
        </header>

        <!-- 已发布文章被改过：线上还是旧内容，这里把话说清楚并给一个直接动作 -->
        <div v-if="canEdit && note.hasUnpublishedEdits" class="notice notice-warn pending-bar">
          <span>这篇已发布后又改过，<strong>线上仍是旧内容</strong>。重新发布后读者才会看到新版本。</span>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="republishing" @click="republish">
            {{ republishing ? '发布中…' : '重新发布' }}
          </button>
        </div>

        <div class="reader-body" :class="{ 'has-outline': outline.length > 2 }">
          <article ref="article" class="prose" v-html="html" @click="onArticleClick" />

          <nav v-if="outline.length > 2" class="outline" aria-label="文章大纲">
            <p class="outline-title">大纲</p>
            <button
              v-for="item in outline"
              :key="item.id"
              class="outline-item"
              :class="{ 'is-active': activeId === item.id }"
              :style="{ paddingLeft: `${(item.level - 1) * 12}px` }"
              type="button"
              @click="jump(item)"
            >
              {{ item.text }}
            </button>
          </nav>
        </div>

        <section v-if="related.length" class="related" aria-label="相关笔记">
          <p class="related-title">相关笔记</p>
          <div class="related-grid">
            <RouterLink v-for="item in related" :key="item.id" class="related-card" :to="`/notes/${item.slug}`">
              <span class="related-name">{{ item.title }}</span>
              <span v-if="item.summary" class="related-summary">{{ item.summary }}</span>
            </RouterLink>
          </div>
        </section>
      </template>
    </section>

    <Transition name="fade">
      <button v-if="showTop" class="to-top" type="button" aria-label="回到顶部" @click="toTop">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 19V5M6 11l6-6 6 6" />
        </svg>
      </button>
    </Transition>

    <Transition name="lightbox">
      <div v-if="lightbox" class="lightbox" @click="lightbox = ''">
        <img :src="lightbox" alt="" />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.reading {
  position: relative;
}

.progress {
  position: fixed;
  top: 55px;
  right: 0;
  left: 0;
  z-index: 19;
  height: 2px;
  background: transparent;
  pointer-events: none;
}

.progress-fill {
  display: block;
  height: 100%;
  background: var(--pen);
  transform-origin: left;
  transition: transform 90ms linear;
}

.reader {
  max-width: 1040px;
}

.reader-head {
  max-width: var(--measure);
  margin-bottom: var(--space-6);
  animation: rise var(--dur-3) var(--ease-out) backwards;
}

.crumb {
  margin-bottom: var(--space-3);
}

.reader-title {
  font-family: var(--font-serif);
  font-size: var(--text-h1);
  line-height: 1.3;
  letter-spacing: 0.005em;
}

.reader-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  color: var(--ink-3);
}

.meta-sep {
  color: var(--hairline);
}

.meta-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-tag {
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--paper-deep);
  font-size: 11px;
}

.reader-actions {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-5);
}

.reader-body {
  display: block;
}

/* 待重发提示条：和正文同宽，贴着标题下面，不抢阅读起点 */
.pending-bar {
  max-width: var(--measure);
  margin-bottom: var(--space-5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.reader-body.has-outline {
  display: grid;
  grid-template-columns: minmax(0, var(--measure)) 180px;
  gap: var(--space-7);
  align-items: start;
}

.prose {
  cursor: default;
}

.prose :deep(img) {
  cursor: zoom-in;
  transition:
    opacity var(--dur-1),
    box-shadow var(--dur-2);
}

.prose :deep(img:hover) {
  opacity: 0.94;
  box-shadow: var(--lift-2);
}

.outline {
  position: sticky;
  top: 80px;
  max-height: calc(100vh - 120px);
  overflow: auto;
  padding-top: var(--space-2);
  padding-left: var(--space-4);
  border-left: 1px solid var(--hairline);
}

.outline-title {
  font-size: var(--text-xs);
  color: var(--ink-3);
  margin-bottom: var(--space-3);
}

.outline-item {
  position: relative;
  display: block;
  width: 100%;
  padding: 5px 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-3);
  text-align: left;
  cursor: pointer;
  transition:
    color var(--dur-1) var(--ease-soft),
    transform var(--dur-2) var(--ease-out);
}

.outline-item:hover {
  color: var(--ink);
  transform: translateX(2px);
}

.outline-item.is-active {
  color: var(--pen);
}

.outline-item.is-active::before {
  content: '';
  position: absolute;
  top: 9px;
  bottom: 9px;
  left: calc(var(--space-4) * -1 - 1px);
  width: 2px;
  border-radius: 1px;
  background: var(--pen);
}

.to-top {
  position: fixed;
  right: var(--space-5);
  bottom: var(--space-6);
  z-index: 25;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--hairline);
  border-radius: 50%;
  background: var(--paper);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--lift-2);
  transition:
    color var(--dur-1),
    border-color var(--dur-1),
    transform var(--dur-2) var(--ease-out);
}

.to-top:hover {
  color: var(--pen);
  border-color: var(--pen);
  transform: translateY(-2px);
}

.to-top svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.fade-enter-active,
.fade-leave-active {
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-2) var(--ease-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

.lightbox {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: var(--space-5);
  background: rgba(12, 16, 17, 0.86);
  backdrop-filter: blur(4px);
  cursor: zoom-out;
}

.lightbox img {
  max-width: min(1100px, 96vw);
  max-height: 92vh;
  width: auto;
  border-radius: 4px;
  box-shadow: var(--lift-3);
}

.lightbox-enter-active,
.lightbox-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.lightbox-enter-active img {
  transition: transform var(--dur-3) var(--ease-out);
}

.lightbox-enter-from,
.lightbox-leave-to {
  opacity: 0;
}

.lightbox-enter-from img {
  transform: scale(0.96);
}

@media (max-width: 960px) {
  .reader-body.has-outline {
    display: block;
  }

  .outline {
    display: none;
  }
}

.sparkle {
  color: var(--pen);
  font-size: 13px;
  margin-right: 4px;
}

/* ───────── 相关笔记 ───────── */
.related {
  max-width: var(--measure);
  margin-top: var(--space-7);
  padding-top: var(--space-5);
  border-top: 1px solid var(--hairline);
}

.related-title {
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  color: var(--ink-3);
  margin-bottom: var(--space-3);
}

.related-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--space-3);
}

.related-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--space-4);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--surface);
  text-decoration: none;
  transition:
    border-color var(--dur-1) var(--ease-soft),
    transform var(--dur-2) var(--ease-out),
    box-shadow var(--dur-2) var(--ease-soft);
}

.related-card:hover {
  border-color: var(--pen);
  transform: translateY(-2px);
  box-shadow: var(--lift-2);
}

.related-name {
  font-family: var(--font-serif);
  color: var(--ink);
  font-size: 15px;
  line-height: 1.5;
}

.related-summary {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--ink-3);
  font-size: var(--text-xs);
  line-height: 1.7;
}
</style>
