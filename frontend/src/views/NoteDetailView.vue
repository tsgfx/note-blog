<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  generateNoteMeta,
  getNote,
  getPublishedNote,
  getPublishedRelatedNotes,
  getRelatedNotes,
  publishNote,
  recordPostView,
  unpublishNote,
  type NoteDetail,
  type RelatedNote,
} from '../api/notes'
import { isLoggedIn } from '../api/auth'
import CommentList from '../components/CommentList.vue'
import { buildOutline, renderMarkdown, type OutlineItem } from '../lib/markdown'
import { notify } from '../lib/feedback'
import { openDrawer } from '../lib/askState'
import { applyArticleSeo, plainText } from '../lib/seo'

const route = useRoute()
const note = ref<NoteDetail | null>(null)
const error = ref('')
const loading = ref(true)
const article = ref<HTMLElement>()
const outline = ref<OutlineItem[]>([])
const activeId = ref('')
const progress = ref(0)
const showTop = ref(false)
const showMobileToc = ref(false)
const linkCopied = ref(false)
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

function recordViewOnce(slug: string) {
  const key = `blog_viewed_${slug}`
  try {
    if (sessionStorage.getItem(key)) return
    sessionStorage.setItem(key, '1')
  } catch {
    // 忽略私密模式或禁用 storage
  }
  recordPostView(slug)
    .then((res) => {
      if (note.value && res.viewCount != null) {
        note.value.viewCount = res.viewCount
      }
    })
    .catch(() => {})
}

async function load() {
  loading.value = true
  error.value = ''
  related.value = []
  try {
    const slug = String(route.params.slug)
    note.value = isPublicRoute.value ? await getPublishedNote(slug) : await getNote(slug)
    if (isPublicRoute.value && note.value) {
      recordViewOnce(note.value.slug)
      // 公开文章用文章自己的标题与摘要：标签页、浏览器历史、分享预览才认得出来
      applyArticleSeo(note.value.title, note.value.summary || plainText(note.value.contentMd))
    }
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
 * 相关笔记：公开页面拉公开推荐接口，登录笔记页拉全库推荐。
 * 失败静默——推荐是锦上添花，不该让读者看到报错。
 */
async function loadRelated() {
  if (!note.value) return
  try {
    related.value = isPublicRoute.value
      ? await getPublishedRelatedNotes(note.value.slug, 4)
      : await getRelatedNotes(note.value.id)
  } catch {
    related.value = []
  }
}

async function copyShareLink() {
  if (!note.value) return
  const url = isPublicRoute.value
    ? `${window.location.origin}/blog/${note.value.slug}`
    : window.location.href
  try {
    await navigator.clipboard.writeText(url)
    linkCopied.value = true
    notify('文章链接已复制到剪贴板', 'success')
    window.setTimeout(() => (linkCopied.value = false), 2000)
  } catch {
    notify('无法复制链接，请手动复制浏览器地址栏', 'error')
  }
}

function jumpFromMobile(item: OutlineItem) {
  jump(item)
  showMobileToc.value = false
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
            <template v-if="note.viewCount != null">
              <span class="meta-sep" aria-hidden="true">·</span>
              <span>{{ note.viewCount }} 次阅读</span>
            </template>
            <span v-if="note.tags.length" class="meta-tags">
              <!-- 公开页的标签是入口：点一下就看同标签的全部文章；管理页保持纯展示 -->
              <template v-if="isPublicRoute">
                <RouterLink
                  v-for="tag in note.tags"
                  :key="tag"
                  class="meta-tag meta-tag-link"
                  :to="{ path: '/blog', query: { tag } }"
                >
                  {{ tag }}
                </RouterLink>
              </template>
              <template v-else>
                <span v-for="tag in note.tags" :key="tag" class="meta-tag">{{ tag }}</span>
              </template>
            </span>
            <button
              type="button"
              class="copy-link-btn"
              title="复制文章分享链接"
              @click="copyShareLink"
            >
              <svg viewBox="0 0 24 24" class="copy-link-icon" aria-hidden="true">
                <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
              </svg>
              <span>{{ linkCopied ? '已复制链接' : '分享' }}</span>
            </button>
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

        <!-- 相邻文章导航（公开文章视图） -->
        <nav v-if="isPublicRoute && (note.prev || note.next)" class="adjacent-nav" aria-label="相邻文章导航">
          <RouterLink
            v-if="note.prev"
            :to="`/blog/${note.prev.slug}`"
            class="adjacent-card adjacent-prev"
          >
            <span class="adjacent-hint">← 上一篇</span>
            <span class="adjacent-title">{{ note.prev.title }}</span>
          </RouterLink>
          <div v-else class="adjacent-empty" />

          <RouterLink
            v-if="note.next"
            :to="`/blog/${note.next.slug}`"
            class="adjacent-card adjacent-next"
          >
            <span class="adjacent-hint">下一篇 →</span>
            <span class="adjacent-title">{{ note.next.title }}</span>
          </RouterLink>
        </nav>

        <section v-if="related.length" class="related" aria-label="相关推荐">
          <p class="related-title">{{ isPublicRoute ? '相关推荐' : '相关笔记' }}</p>
          <div class="related-grid">
            <RouterLink
              v-for="item in related"
              :key="item.id"
              class="related-card"
              :to="isPublicRoute ? `/blog/${item.slug}` : `/notes/${item.slug}`"
            >
              <span class="related-name">{{ item.title }}</span>
              <span v-if="item.summary" class="related-summary">{{ item.summary }}</span>
            </RouterLink>
          </div>
        </section>

        <!-- 评论只出现在公开文章下：/notes/:slug 是管理员的私有视图，
             在那里显示公开评论区会让人以为游客能看到草稿 -->
        <CommentList v-if="isPublicRoute && note" :slug="note.slug" />
      </template>
    </section>

    <!-- 移动端悬浮目录按钮（大纲项 > 2 时出现） -->
    <button
      v-if="outline.length > 2"
      class="mobile-toc-btn"
      type="button"
      aria-label="文章目录"
      @click="showMobileToc = true"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true" class="mobile-toc-icon">
        <path d="M4 6h16M4 12h10M4 18h14" />
      </svg>
      <span>目录</span>
    </button>

    <!-- 移动端目录弹层 -->
    <Transition name="fade">
      <div v-if="showMobileToc" class="toc-sheet-backdrop" @click="showMobileToc = false">
        <div class="toc-sheet" @click.stop>
          <div class="toc-sheet-header">
            <span class="toc-sheet-title">文章目录</span>
            <button type="button" class="toc-sheet-close" aria-label="关闭目录" @click="showMobileToc = false">×</button>
          </div>
          <div class="toc-sheet-body">
            <button
              v-for="item in outline"
              :key="item.id"
              class="toc-sheet-item"
              :class="{ 'is-active': activeId === item.id }"
              :style="{ paddingLeft: `${(item.level - 1) * 14 + 10}px` }"
              type="button"
              @click="jumpFromMobile(item)"
            >
              {{ item.text }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

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

/* 可点的标签：底纹在悬浮时转成主题色，和不可点的纯展示标签区分开 */
.meta-tag-link {
  color: var(--ink-3);
  transition:
    color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.meta-tag-link:hover {
  background: var(--pen-soft);
  color: var(--pen);
  text-decoration: none;
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

/* ───────── 相邻文章导航 ───────── */
.adjacent-nav {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  max-width: var(--measure);
  margin-top: var(--space-7);
  padding-top: var(--space-6);
  border-top: 1px solid var(--hairline);
}

.adjacent-card {
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

.adjacent-card:hover {
  border-color: var(--pen);
  transform: translateY(-2px);
  box-shadow: var(--lift-2);
}

.adjacent-next {
  text-align: right;
}

.adjacent-hint {
  font-size: var(--text-xs);
  color: var(--ink-3);
  letter-spacing: 0.05em;
}

.adjacent-title {
  font-family: var(--font-serif);
  color: var(--ink);
  font-size: 15px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

@media (max-width: 640px) {
  .adjacent-nav {
    grid-template-columns: 1fr;
  }
  .adjacent-next {
    text-align: left;
  }
}

/* ───────── 分享链接按钮 ───────── */
.copy-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--ink-3);
  font-size: 11px;
  cursor: pointer;
  transition:
    color var(--dur-1),
    border-color var(--dur-1),
    background-color var(--dur-1);
}

.copy-link-btn:hover {
  color: var(--pen);
  border-color: var(--pen);
  background: var(--pen-soft);
}

.copy-link-icon {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* ───────── 移动端目录悬浮与抽屉 ───────── */
.mobile-toc-btn {
  display: none;
}

@media (max-width: 960px) {
  .mobile-toc-btn {
    position: fixed;
    left: var(--space-4);
    bottom: var(--space-6);
    z-index: 25;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 38px;
    padding: 0 14px;
    border: 1px solid var(--hairline);
    border-radius: 19px;
    background: var(--paper);
    color: var(--ink-2);
    font-size: var(--text-xs);
    font-weight: 500;
    cursor: pointer;
    box-shadow: var(--lift-2);
    transition:
      color var(--dur-1),
      border-color var(--dur-1),
      transform var(--dur-2) var(--ease-out);
  }

  .mobile-toc-btn:hover {
    color: var(--pen);
    border-color: var(--pen);
    transform: translateY(-2px);
  }

  .mobile-toc-icon {
    width: 15px;
    height: 15px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
}

.toc-sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(12, 16, 17, 0.45);
  backdrop-filter: blur(2px);
  display: flex;
  justify-content: center;
  align-items: flex-end;
}

.toc-sheet {
  width: 100%;
  max-width: 540px;
  max-height: 75vh;
  background: var(--paper);
  border-top-left-radius: 16px;
  border-top-right-radius: 16px;
  box-shadow: var(--lift-3);
  display: flex;
  flex-direction: column;
  animation: slideUp var(--dur-3) var(--ease-out);
}

@keyframes slideUp {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.toc-sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--hairline);
}

.toc-sheet-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink);
}

.toc-sheet-close {
  border: none;
  background: none;
  font-size: 20px;
  color: var(--ink-3);
  cursor: pointer;
  padding: 2px 6px;
  line-height: 1;
}

.toc-sheet-close:hover {
  color: var(--ink);
}

.toc-sheet-body {
  overflow-y: auto;
  padding: var(--space-3) var(--space-4) var(--space-6);
}

.toc-sheet-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: none;
  font-size: var(--text-sm);
  text-align: left;
  color: var(--ink-2);
  cursor: pointer;
  border-radius: var(--radius);
  transition:
    background-color var(--dur-1),
    color var(--dur-1);
}

.toc-sheet-item:hover {
  background: var(--row-hover);
  color: var(--pen);
}

.toc-sheet-item.is-active {
  color: var(--pen);
  font-weight: 500;
  background: var(--pen-soft);
}
</style>
