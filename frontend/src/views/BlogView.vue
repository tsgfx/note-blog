<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  listPublishedNotes,
  listPublishedTags,
  listRecommendations,
  type NoteSummary,
  type TagCount,
} from '../api/notes'
import { isLoggedIn } from '../api/auth'
import LoadingRows from '../components/LoadingRows.vue'

const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 20

const posts = ref<NoteSummary[]>([])
const hotTags = ref<TagCount[]>([])
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const signedIn = isLoggedIn()
const historyCount = ref(0)

const currentTag = computed(() => ((route.query.tag as string) || '').trim())
const currentKeyword = computed(() => ((route.query.keyword as string) || '').trim())
const currentSort = computed<'recent' | 'views' | 'recommended'>(() => {
  if (route.query.sort === 'views') return 'views'
  if (route.query.sort === 'recommended') return 'recommended'
  return 'recent'
})
const searchInput = ref(currentKeyword.value)

const hasFilter = computed(() => Boolean(currentTag.value || currentKeyword.value))
const hasPrev = computed(() => page.value > 0)
const hasNext = computed(() => page.value + 1 < totalPages.value)

function syncHistoryCount() {
  try {
    const raw = localStorage.getItem('notesblog_read_history')
    if (raw) {
      const list = JSON.parse(raw)
      historyCount.value = Array.isArray(list) ? list.length : 0
    } else {
      historyCount.value = 0
    }
  } catch {
    historyCount.value = 0
  }
}

/** 分组：最新排序按年份汇集；热门与推荐独立展现完整信息流 */
const groups = computed(() => {
  if (currentSort.value === 'views') {
    return [{ year: '热门排行', items: posts.value }]
  }
  if (currentSort.value === 'recommended') {
    return [{ year: '为你推荐', items: posts.value }]
  }
  const map = new Map<string, NoteSummary[]>()
  posts.value.forEach((post) => {
    const year = (post.publishedAt ?? '').slice(0, 4) || '未标注'
    const bucket = map.get(year)
    if (bucket) bucket.push(post)
    else map.set(year, [post])
  })
  return [...map.entries()].map(([year, items]) => ({ year, items }))
})

function switchSort(sort: 'recent' | 'views' | 'recommended') {
  if (currentSort.value === sort) return
  router.push({
    query: {
      ...route.query,
      sort: sort === 'recent' ? undefined : sort,
      page: undefined,
    },
  })
}

function monthDay(value?: string): string {
  if (!value) return ''
  return `${value.slice(5, 7)}-${value.slice(8, 10)}`
}

const HTML_ESCAPE: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => HTML_ESCAPE[c] ?? c)
}

/**
 * 把正文片段里的关键词包成 <mark>。
 *
 * <p>先转义再拼标签：片段来自笔记正文，虽然作者是自己人，但 v-html 一旦漏了转义，
 * 一篇写了 `<script>` 的笔记就能在博客页执行。关键词同样要转义，否则含 `<` 的词会破坏标记。
 */
function highlight(text: string): string {
  const safe = escapeHtml(text)
  const kw = escapeHtml(currentKeyword.value).trim()
  if (!kw) return safe
  const pattern = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(pattern, 'gi'), (hit) => `<mark>${hit}</mark>`)
}

function applySearch() {
  const kw = searchInput.value.trim()
  router.push({
    query: {
      ...route.query,
      keyword: kw || undefined,
      page: undefined,
    },
  })
}

function filterTag(tag: string) {
  if (currentTag.value === tag) {
    clearTag()
    return
  }
  router.push({
    query: {
      ...route.query,
      tag,
      page: undefined,
    },
  })
}

function clearTag() {
  const q = { ...route.query }
  delete q.tag
  delete q.page
  router.push({ query: q })
}

function clearKeyword() {
  searchInput.value = ''
  const q = { ...route.query }
  delete q.keyword
  delete q.page
  router.push({ query: q })
}

function clearAllFilters() {
  searchInput.value = ''
  // 只清筛选，排序是读者主动选的视角，不该被一起重置
  router.push({ query: currentSort.value === 'views' ? { sort: 'views' } : {} })
}

async function load(target = 0) {
  // 首屏才用骨架；换筛选/翻页时保留旧列表，只把透明度压下去。
  // 否则每点一次标签整页高度塌成骨架，再撑回来，跳得人眼晕。
  const firstPaint = posts.value.length === 0
  if (firstPaint) loading.value = true
  else refreshing.value = true
  error.value = ''
  try {
    if (currentSort.value === 'recommended') {
      syncHistoryCount()
      let historyList: string[] = []
      try {
        const raw = localStorage.getItem('notesblog_read_history')
        if (raw) historyList = JSON.parse(raw)
      } catch {
        // 隐私模式下静默
      }
      const result = await listRecommendations(historyList, PAGE_SIZE)
      posts.value = result
      page.value = 0
      totalPages.value = 1
      totalElements.value = result.length
    } else {
      const result = await listPublishedNotes({
        page: target,
        size: PAGE_SIZE,
        tag: currentTag.value || undefined,
        keyword: currentKeyword.value || undefined,
        sort: currentSort.value,
      })
      posts.value = result.content
      page.value = result.number
      totalPages.value = result.totalPages
      totalElements.value = result.totalElements
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function go(target: number) {
  router.push({
    query: {
      ...route.query,
      page: target > 0 ? String(target) : undefined,
    },
  })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => [route.query.tag, route.query.keyword, route.query.sort, route.query.page],
  () => {
    searchInput.value = currentKeyword.value
    const p = parseInt(route.query.page as string, 10) || 0
    load(p)
  },
)

onMounted(() => {
  const p = parseInt(route.query.page as string, 10) || 0
  load(p)
  // 标签云只是入口提示，失败不打扰读者——列表本身照常可用
  listPublishedTags(12)
    .then((tags) => (hotTags.value = tags))
    .catch(() => (hotTags.value = []))
})
</script>

<template>
  <section class="page blog" :class="{ 'is-refreshing': refreshing }">
    <header class="page-head">
      <div>
        <h1 class="page-title">文章</h1>
        <p class="page-note">
          学习和项目中整理过的笔记，公开的部分在这里。<span v-if="totalElements">共 {{ totalElements }} 篇。</span>
        </p>
      </div>

      <div class="head-actions">
        <div class="sort-tabs" role="tablist" aria-label="文章排序">
          <button
            type="button"
            role="tab"
            class="sort-tab"
            :class="{ 'is-active': currentSort === 'recent' }"
            :aria-selected="currentSort === 'recent'"
            @click="switchSort('recent')"
          >
            最新
          </button>
          <button
            type="button"
            role="tab"
            class="sort-tab"
            :class="{ 'is-active': currentSort === 'views' }"
            :aria-selected="currentSort === 'views'"
            @click="switchSort('views')"
          >
            热门
          </button>
          <button
            type="button"
            role="tab"
            class="sort-tab"
            :class="{ 'is-active': currentSort === 'recommended' }"
            :aria-selected="currentSort === 'recommended'"
            @click="switchSort('recommended')"
          >
            <span class="recommend-sparkle" aria-hidden="true">✦</span> 推荐
          </button>
        </div>

        <form class="search-box" @submit.prevent="applySearch">
          <svg viewBox="0 0 24 24" aria-hidden="true" class="search-icon">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            v-model="searchInput"
            class="search-input"
            type="search"
            placeholder="搜索公开文章…"
            aria-label="搜索公开文章"
            @keydown.enter.prevent="applySearch"
          />
        </form>

        <a class="feed" href="/api/blog/rss.xml" target="_blank" rel="noopener">
          <svg viewBox="0 0 24 24" aria-hidden="true" class="feed-icon">
            <circle cx="6.2" cy="17.8" r="1.8" />
            <path d="M5 11.2a7.8 7.8 0 0 1 7.8 7.8M5 5.4A13.6 13.6 0 0 1 18.6 19" />
          </svg>
          订阅 RSS
        </a>
      </div>
    </header>

    <div v-if="hotTags.length" class="tag-cloud" aria-label="热门标签">
      <span class="tag-cloud-label">标签</span>
      <button
        v-for="item in hotTags"
        :key="item.name"
        type="button"
        class="cloud-tag"
        :class="{ 'is-active': currentTag === item.name }"
        :aria-pressed="currentTag === item.name"
        @click="filterTag(item.name)"
      >
        {{ item.name }}<span class="cloud-count">{{ item.count }}</span>
      </button>
    </div>

    <div v-if="hasFilter" class="active-filter-bar rise">
      <span class="filter-desc">
        筛选：
        <span v-if="currentTag" class="filter-chip">
          #{{ currentTag }}
          <button type="button" class="chip-remove" aria-label="移除标签筛选" @click="clearTag">×</button>
        </span>
        <span v-if="currentKeyword" class="filter-chip">
          "{{ currentKeyword }}"
          <button type="button" class="chip-remove" aria-label="移除关键词筛选" @click="clearKeyword">×</button>
        </span>
      </span>
      <button type="button" class="btn btn-ghost btn-xs" @click="clearAllFilters">清除筛选</button>
    </div>

    <div v-if="currentSort === 'recommended'" class="recommend-banner rise">
      <span class="recommend-banner-sparkle" aria-hidden="true">✦</span>
      <span class="recommend-banner-text">
        {{
          historyCount > 0
            ? `基于你最近阅读的 ${historyCount} 篇足迹智能推选 · 多类目打散重排`
            : '当前基于全库热门与精选文章推荐；阅读几篇后，系统将自动生成专属于你的个性化推荐流'
        }}
      </span>
    </div>

    <LoadingRows v-if="loading" :rows="6" variant="post" />
    <p v-else-if="error" class="error-text">{{ error }}</p>

    <template v-else-if="posts.length">
      <section v-for="group in groups" :key="group.year" class="year">
        <h2 class="year-label">{{ group.year }}</h2>
        <ol class="posts">
          <li
            v-for="(post, index) in group.items"
            :key="post.id"
            class="post rise"
            :style="{ '--i': Math.min(index, 10) }"
          >
            <div class="post-aside">
              <time class="post-date" :datetime="post.publishedAt">{{ monthDay(post.publishedAt) }}</time>
              <span v-if="post.viewCount != null" class="post-views" :title="`浏览量 ${post.viewCount}`">
                {{ post.viewCount }} 阅
              </span>
            </div>
            <div class="post-body">
              <div v-if="post.recommendReason" class="post-recommend-tag">
                <span class="rec-sparkle" aria-hidden="true">✦</span> {{ post.recommendReason }}
              </div>
              <RouterLink class="post-title" :to="`/blog/${post.slug}`">
                <span class="post-title-text">{{ post.title }}</span>
              </RouterLink>
              <p v-if="post.summary" class="post-summary">{{ post.summary }}</p>
              <!-- 关键词命中的是正文而不是摘要时，把那一句带出来，
                   否则读者看到一条"摘要里没有关键词"的结果，会以为搜索不准 -->
              <p v-else-if="post.matchSnippet" class="post-snippet">
                <span class="snippet-label">正文命中</span>
                <span v-html="highlight(post.matchSnippet)" />
              </p>
              <div v-if="post.tags.length" class="post-tags">
                <button
                  v-for="tag in post.tags.slice(0, 4)"
                  :key="tag"
                  type="button"
                  class="post-tag"
                  :class="{ 'is-active': currentTag === tag }"
                  @click.prevent="filterTag(tag)"
                >
                  #{{ tag }}
                </button>
              </div>
            </div>
          </li>
        </ol>
      </section>

      <nav v-if="totalPages > 1" class="pager" aria-label="翻页">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasPrev" @click="go(page - 1)">上一页</button>
        <span class="pager-info">第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasNext" @click="go(page + 1)">下一页</button>
      </nav>
    </template>

    <div v-else class="empty-state">
      <p class="empty-title">{{ hasFilter ? '没有找到符合条件的文章' : '还没有公开的文章' }}</p>
      <p class="empty-hint">
        {{
          hasFilter
            ? '换个关键词或标签试试看。'
            : signedIn
              ? '在「笔记」里点击「发布」，文章就会出现在这里。'
              : '作者还没有发布任何内容。'
        }}
      </p>
      <div v-if="hasFilter" class="empty-actions">
        <button class="btn btn-primary btn-sm" type="button" @click="clearAllFilters">清除筛选条件</button>
      </div>
      <div v-else-if="signedIn" class="empty-actions">
        <RouterLink class="btn btn-primary btn-sm" to="/notes">去笔记里挑几篇</RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.sort-tabs {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--surface);
  gap: 2px;
}

.sort-tab {
  border: none;
  background: transparent;
  padding: 3px 9px;
  border-radius: calc(var(--radius) - 2px);
  font-size: var(--text-xs);
  color: var(--ink-3);
  cursor: pointer;
  transition:
    background-color var(--dur-1) var(--ease-soft),
    color var(--dur-1) var(--ease-soft);
}

.sort-tab:hover {
  color: var(--ink);
}

.sort-tab.is-active {
  background: var(--paper);
  color: var(--pen);
  font-weight: 500;
  box-shadow: var(--lift-1);
}

.recommend-sparkle {
  color: var(--pen);
  font-size: 11px;
}

/* ───────── 推荐说明条 ───────── */
.recommend-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
  padding: 8px 14px;
  background: var(--pen-soft, rgba(46, 117, 182, 0.08));
  border: 1px solid var(--pen-soft, rgba(46, 117, 182, 0.2));
  border-radius: var(--radius-sm, 6px);
  color: var(--pen-ink, var(--pen));
  font-size: var(--text-xs);
  line-height: 1.5;
}

.recommend-banner-sparkle {
  font-size: 12px;
  color: var(--pen);
}

.recommend-banner-text {
  font-weight: 500;
}

.post-recommend-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--pen);
  background: var(--pen-soft, rgba(46, 117, 182, 0.08));
  padding: 1px 6px;
  border-radius: 4px;
  width: fit-content;
  margin-bottom: 4px;
  font-weight: 500;
}

.rec-sparkle {
  font-size: 10px;
}

.search-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink-2);
  transition:
    border-color var(--dur-1) var(--ease-soft),
    box-shadow var(--dur-1) var(--ease-soft);
}

.search-box:focus-within {
  border-color: var(--pen);
  box-shadow: 0 0 0 2px var(--pen-soft);
}

.search-icon {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: var(--ink-3);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  flex-shrink: 0;
}

.search-input {
  border: none;
  background: transparent;
  color: var(--ink);
  font-size: var(--text-sm);
  outline: none;
  width: 120px;
  transition: width var(--dur-2) var(--ease-out);
}

.search-input:focus {
  width: 170px;
}

.search-input::placeholder {
  color: var(--ink-3);
}

.tag-cloud {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--space-5);
}

.tag-cloud-label {
  font-size: var(--text-xs);
  color: var(--ink-3);
  letter-spacing: 0.08em;
  margin-right: 2px;
}

.cloud-tag {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 2px 9px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: transparent;
  color: var(--ink-2);
  font-family: inherit;
  font-size: var(--text-xs);
  cursor: pointer;
  transition:
    color var(--dur-1) var(--ease-soft),
    border-color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.cloud-tag:hover,
.cloud-tag.is-active {
  color: var(--pen);
  border-color: var(--pen);
  background: var(--pen-soft);
}

.cloud-count {
  font-size: 10px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.cloud-tag.is-active .cloud-count,
.cloud-tag:hover .cloud-count {
  color: var(--pen);
}

.active-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius);
  background: var(--pen-soft);
  font-size: var(--text-sm);
  color: var(--pen);
}

.filter-desc {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink);
  font-size: var(--text-xs);
  font-weight: 500;
  box-shadow: var(--lift-1);
}

.chip-remove {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--ink-3);
  font-size: 14px;
  line-height: 1;
  padding: 0 1px;
}

.chip-remove:hover {
  color: var(--pen);
}

.feed {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  color: var(--ink-2);
  white-space: nowrap;
  transition:
    color var(--dur-1) var(--ease-soft),
    border-color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft);
}

.feed:hover {
  color: var(--pen);
  border-color: var(--pen);
  background: var(--row-hover);
  text-decoration: none;
}

.feed-icon {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
}

/* 换筛选时的过渡：旧列表留在原位变淡，新结果到了再恢复。
   不给 pointer-events: none —— 读者在加载期间仍可以点开已经看到的文章。 */
.is-refreshing .year,
.is-refreshing .pager,
.is-refreshing .empty-state {
  opacity: 0.5;
  transition: opacity var(--dur-1) var(--ease-soft);
}

.year + .year {
  margin-top: var(--space-7);
}

.year-label {
  margin-bottom: var(--space-2);
  font-family: var(--font-serif);
  font-size: 34px;
  font-weight: 500;
  color: var(--ink-3);
  letter-spacing: 0.02em;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.posts {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--hairline);
}

.post {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: var(--space-5);
  padding: var(--space-5) var(--space-4);
  margin: 0 calc(var(--space-4) * -1);
  border-bottom: 1px solid var(--hairline);
  transition: background-color var(--dur-1) var(--ease-soft);
}

.post:hover {
  background: var(--row-hover);
}

.post-aside {
  display: flex;
  flex-direction: column;
  padding-top: 7px;
  gap: 4px;
}

.post-date {
  font-size: var(--text-sm);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  transition: color var(--dur-1);
}

.post:hover .post-date {
  color: var(--pen);
}

.post-views {
  font-size: 11px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}

.post-body {
  min-width: 0;
}

.post-title {
  display: inline-block;
  font-family: var(--font-serif);
  font-size: 22px;
  line-height: 1.4;
  color: var(--ink);
  max-width: 100%;
}

.post-title-text {
  background-image: linear-gradient(var(--pen), var(--pen));
  background-repeat: no-repeat;
  background-position: 0 100%;
  background-size: 0% 1px;
  transition:
    background-size var(--dur-3) var(--ease-out),
    color var(--dur-1) var(--ease-soft);
}

.post-title:hover {
  text-decoration: none;
}

.post-title:hover .post-title-text {
  color: var(--pen);
  background-size: 100% 1px;
}

.post-summary {
  margin-top: 6px;
  color: var(--ink-2);
  font-size: var(--text-sm);
  line-height: 1.7;
  max-width: 38rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 正文命中片段：和摘要同样两行，但带一个来源标记，读者知道这是搜索上下文 */
.post-snippet {
  margin-top: 6px;
  color: var(--ink-2);
  font-size: var(--text-sm);
  line-height: 1.7;
  max-width: 38rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.snippet-label {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--paper-deep);
  color: var(--ink-3);
  font-size: 11px;
  vertical-align: 1px;
}

.post-snippet :deep(mark) {
  padding: 0 1px;
  border-radius: 2px;
  background: var(--pen-soft);
  color: var(--pen);
}

.post-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--space-3);
}

.post-tag {
  cursor: pointer;
  border: none;
  font-family: inherit;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--paper-deep);
  color: var(--ink-3);
  font-size: 11px;
  letter-spacing: 0.01em;
  transition:
    background-color var(--dur-1),
    color var(--dur-1);
}

.post-tag:hover,
.post-tag.is-active {
  background: var(--pen-soft);
  color: var(--pen);
}

.empty-state {
  padding: var(--space-7) 0;
  text-align: center;
}

.empty-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
}

.empty-hint {
  margin-top: var(--space-2);
  color: var(--ink-3);
  font-size: var(--text-sm);
}

.empty-actions {
  margin-top: var(--space-5);
}

.pager {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  justify-content: center;
  margin-top: var(--space-6);
}

.pager-info {
  font-size: var(--text-sm);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 640px) {
  .post {
    grid-template-columns: 52px minmax(0, 1fr);
    gap: var(--space-3);
  }

  .year-label {
    font-size: 26px;
  }

  .search-input {
    width: 90px;
  }
  .search-input:focus {
    width: 130px;
  }
}
</style>
