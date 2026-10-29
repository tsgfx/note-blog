<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listPublishedNotes, type NoteSummary } from '../api/notes'
import { isLoggedIn } from '../api/auth'
import LoadingRows from '../components/LoadingRows.vue'

const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 20

const posts = ref<NoteSummary[]>([])
const loading = ref(true)
const error = ref('')
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const signedIn = isLoggedIn()

const currentTag = computed(() => ((route.query.tag as string) || '').trim())
const currentKeyword = computed(() => ((route.query.keyword as string) || '').trim())
const currentSort = computed<'recent' | 'views'>(() => (route.query.sort === 'views' ? 'views' : 'recent'))
const searchInput = ref(currentKeyword.value)

const hasFilter = computed(() => Boolean(currentTag.value || currentKeyword.value))
const hasPrev = computed(() => page.value > 0)
const hasNext = computed(() => page.value + 1 < totalPages.value)

/** 年份分组：最新排序按年份清晰汇集；热门排序按榜单直观展现 */
const groups = computed(() => {
  if (currentSort.value === 'views') {
    return [{ year: '热门排行', items: posts.value }]
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

function switchSort(sort: 'recent' | 'views') {
  if (currentSort.value === sort) return
  router.push({
    query: {
      ...route.query,
      sort: sort === 'views' ? 'views' : undefined,
      page: undefined,
    },
  })
}

function monthDay(value?: string): string {
  if (!value) return ''
  return `${value.slice(5, 7)}-${value.slice(8, 10)}`
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
  router.push({ query: {} })
}

async function load(target = 0) {
  loading.value = true
  error.value = ''
  try {
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
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
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
})
</script>

<template>
  <section class="page blog">
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
              <RouterLink class="post-title" :to="`/blog/${post.slug}`">
                <span class="post-title-text">{{ post.title }}</span>
              </RouterLink>
              <p v-if="post.summary" class="post-summary">{{ post.summary }}</p>
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
        {{ hasFilter ? '换个关键词或标签试试看。' : (signedIn ? '在「笔记」里点击「发布」，文章就会出现在这里。' : '作者还没有发布任何内容。') }}
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
