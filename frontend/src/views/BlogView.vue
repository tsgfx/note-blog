<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { listPublishedNotes, type NoteSummary } from '../api/notes'
import { isLoggedIn } from '../api/auth'
import LoadingRows from '../components/LoadingRows.vue'

const PAGE_SIZE = 20

const posts = ref<NoteSummary[]>([])
const loading = ref(true)
const error = ref('')
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const signedIn = isLoggedIn()

const hasPrev = computed(() => page.value > 0)
const hasNext = computed(() => page.value + 1 < totalPages.value)

/** 年份分组：695 篇的库跨好几年时，光看一串日期找东西很费劲 */
const groups = computed(() => {
  const map = new Map<string, NoteSummary[]>()
  posts.value.forEach((post) => {
    const year = (post.publishedAt ?? '').slice(0, 4) || '未标注'
    const bucket = map.get(year)
    if (bucket) bucket.push(post)
    else map.set(year, [post])
  })
  return [...map.entries()].map(([year, items]) => ({ year, items }))
})

function monthDay(value?: string): string {
  if (!value) return ''
  return `${value.slice(5, 7)}-${value.slice(8, 10)}`
}

async function load(target = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await listPublishedNotes(target, PAGE_SIZE)
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
  load(target)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => load(0))
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
      <a class="feed" href="/api/blog/rss.xml" target="_blank" rel="noopener">
        <svg viewBox="0 0 24 24" aria-hidden="true" class="feed-icon">
          <circle cx="6.2" cy="17.8" r="1.8" />
          <path d="M5 11.2a7.8 7.8 0 0 1 7.8 7.8M5 5.4A13.6 13.6 0 0 1 18.6 19" />
        </svg>
        订阅 RSS
      </a>
    </header>

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
            <time class="post-date" :datetime="post.publishedAt">{{ monthDay(post.publishedAt) }}</time>
            <div class="post-body">
              <RouterLink class="post-title" :to="`/blog/${post.slug}`">
                <span class="post-title-text">{{ post.title }}</span>
              </RouterLink>
              <p v-if="post.summary" class="post-summary">{{ post.summary }}</p>
              <div v-if="post.tags.length" class="post-tags">
                <span v-for="tag in post.tags.slice(0, 4)" :key="tag" class="post-tag">{{ tag }}</span>
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
      <p class="empty-title">还没有公开的文章</p>
      <p class="empty-hint">
        {{ signedIn ? '在「笔记」里点击「发布」，文章就会出现在这里。' : '作者还没有发布任何内容。' }}
      </p>
      <div v-if="signedIn" class="empty-actions">
        <RouterLink class="btn btn-primary btn-sm" to="/notes">去笔记里挑几篇</RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
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

/* 年份不独占一整行高度，贴在列表左上方，视线落点更连续 */
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

.post-date {
  padding-top: 7px;
  font-size: var(--text-sm);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  transition: color var(--dur-1);
}

.post:hover .post-date {
  color: var(--pen);
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

/* 标题下划线从左侧生长，代替整行变色——比换字色更像"这里可以点" */
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

.post:hover .post-tag {
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
}
</style>
