<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  deleteNote,
  generateMetaBatch,
  listFolders,
  listNotes,
  listTags,
  preflightNotes,
  publishNote,
  publishNotes,
  unpublishNote,
  unpublishNotes,
  type FolderNode,
  type NoteSummary,
  type PreflightReport,
  type TagCount,
} from '../api/notes'
import { confirmAction, notify } from '../lib/feedback'
import { openDrawer } from '../lib/askState'
import { collapseAll, revealFolder } from '../lib/folderTree'
import FolderTree from '../components/FolderTree.vue'
import LoadingRows from '../components/LoadingRows.vue'

const PAGE_SIZE = 40
/** 分类栏默认展示多少个标签，剩下的收进「更多」 */
const TAG_LIMIT = 18

const router = useRouter()
const route = useRoute()

const notes = ref<NoteSummary[]>([])
const loading = ref(true)
const error = ref('')
/** 当前选中的目录路径；空字符串 = 全部。第一段就是原来的「库」 */
const activePath = ref<string>('')
const status = ref<'ALL' | 'PUBLISHED' | 'DRAFT'>('ALL')
const query = ref('')
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const folders = ref<FolderNode[]>([])
const tags = ref<TagCount[]>([])
const activeTags = ref<string[]>([])
const tagsExpanded = ref(false)
const selected = ref<Set<number>>(new Set())

const searchEl = ref<HTMLInputElement>()
const listEl = ref<HTMLElement>()
const focusedIndex = ref(-1)

const selectionCount = computed(() => selected.value.size)

const allVisibleSelected = computed(
  () => notes.value.length > 0 && notes.value.every((note) => selected.value.has(note.id)),
)

const hasPrev = computed(() => page.value > 0)
const hasNext = computed(() => page.value + 1 < totalPages.value)

/** 当前是否处于筛选状态。用于决定副标题说什么。 */
const filtering = computed(() =>
  Boolean(activePath.value || query.value.trim() || status.value !== 'ALL' || activeTags.value.length),
)

/** 「全部」的篇数取树里各顶层节点之和：totalElements 是筛选后的数，会跟着目录变小 */
const allNotes = computed(() => folders.value.reduce((sum, node) => sum + node.count, 0) || totalElements.value)

/** 折叠时只显示前 N 个标签 */
const visibleTags = computed(() => (tagsExpanded.value ? tags.value : tags.value.slice(0, TAG_LIMIT)))

function folderOf(note: NoteSummary): string {
  if (!note.sourcePath) return '新建笔记'
  const parts = note.sourcePath.split('/')
  parts.pop()
  if (!parts.length) return '根目录'

  // 已经按目录筛过之后，每行再顶着整条路径念一遍没有信息量，
  // 只显示相对当前目录的那一段；直接放在该目录下的显示「本目录」
  const scope = activePath.value ? activePath.value.split('/') : []
  const inScope = scope.length > 0 && scope.every((segment, index) => parts[index] === segment)
  const relative = inScope ? parts.slice(scope.length) : parts
  return relative.join(' / ') || '本目录'
}

function dateOf(value?: string): string {
  return value ? value.slice(0, 10) : ''
}

/**
 * 拉取当前页。筛选必须交给服务端：手里只有当前页数据，
 * 在本地过滤只能筛出"当前页里的结果"。
 */
async function load(target = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await listNotes({
      status: status.value === 'ALL' ? undefined : status.value,
      path: activePath.value || undefined,
      q: query.value.trim() || undefined,
      tag: activeTags.value.length ? activeTags.value : undefined,
      page: target,
      size: PAGE_SIZE,
    })
    notes.value = result.content
    page.value = result.number
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
    focusedIndex.value = -1
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/**
 * 目录树。带状态一起取：切到「草稿」时树上的数字也要变，
 * 否则会「树上写 12 篇、点进去 3 篇」，比没有导航更让人困惑。
 */
async function loadFolders() {
  try {
    folders.value = await listFolders({
      status: status.value === 'ALL' ? undefined : status.value,
    })
  } catch {
    // 侧边栏统计失败不该挡着看列表，静默降级成空树
    folders.value = []
  }
}

/**
 * 分类栏的数据随「库」与「状态」变化：在 c++ 库里只该看到 c++ 的标签，
 * 否则会出现「宋词」这种点进去 0 篇的选项。
 */
async function loadTags() {
  try {
    tags.value = await listTags({
      status: status.value === 'ALL' ? undefined : status.value,
      path: activePath.value || undefined,
    })
    // 切库后原来选中的标签可能已经不存在了，清掉避免空结果
    const names = new Set(tags.value.map((t) => t.name))
    activeTags.value = activeTags.value.filter((name) => names.has(name))
  } catch {
    tags.value = []
  }
}

/** 点标签 = 多选（AND）：同时选「链表」和「力扣」= 同时带这两个标签 */
function toggleTag(name: string) {
  activeTags.value = activeTags.value.includes(name)
    ? activeTags.value.filter((item) => item !== name)
    : [...activeTags.value, name]
  clearSelection()
  load(0)
}

function clearTags() {
  activeTags.value = []
  clearSelection()
  load(0)
}

function go(target: number) {
  load(target)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function toggleSelect(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleSelectAllVisible() {
  const next = new Set(selected.value)
  if (allVisibleSelected.value) notes.value.forEach((note) => next.delete(note.id))
  else notes.value.forEach((note) => next.add(note.id))
  selected.value = next
}

function clearSelection() {
  selected.value = new Set()
}

/**
 * 选中一个目录（空字符串 = 全部）。
 *
 * 三件事必须一起做：展开祖先（不然高亮藏在折叠里）、写回 URL（可分享/可刷新）、
 * 清掉标签（上一层的标签在新目录里可能一篇都没有，留着必然筛出空结果）。
 */
function pickFolder(path: string) {
  activePath.value = path
  if (path) revealFolder(path)
  else collapseAll()
  activeTags.value = []
  clearSelection()
  syncPathQuery()
  load(0)
  loadTags()
}

/** 目录写进 URL：刷新、分享、从详情页回来都能回到同一个位置 */
function syncPathQuery() {
  const current = typeof route.query.path === 'string' ? route.query.path : ''
  if (current === activePath.value) return
  const next = { ...route.query }
  if (activePath.value) next.path = activePath.value
  else delete next.path
  void router.replace({ query: next })
}

function pickStatus(next: 'ALL' | 'PUBLISHED' | 'DRAFT') {
  status.value = next
  clearSelection()
  load(0)
  loadTags()
  // 树上的篇数跟着状态变
  loadFolders()
}

/** 一次性清掉三个筛选条件。分开调用会触发三次请求，所以合成一个。 */
function resetFilters() {
  clearTimeout(searchTimer)
  activePath.value = ''
  collapseAll()
  syncPathQuery()
  status.value = 'ALL'
  query.value = ''
  activeTags.value = []
  clearSelection()
  load(0)
  loadTags()
  loadFolders()
}

function open(note: NoteSummary) {
  router.push(`/notes/${note.slug}`)
}

/** 点行即打开，同时记住位置——从详情返回时列表还停在原处 */
function pickAndOpen(note: NoteSummary, index: number) {
  focusedIndex.value = index
  open(note)
}

/** 键盘选中第 index 行：只移动高亮，不打开，避免一路误触 */
function focusRow(index: number) {
  if (!notes.value.length) return
  const next = Math.max(0, Math.min(notes.value.length - 1, index))
  focusedIndex.value = next
  const rows = listEl.value?.querySelectorAll<HTMLElement>('.row')
  rows?.[next]?.scrollIntoView({ block: 'nearest' })
}

// 搜索框输入：防抖 300ms，避免每敲一个字发一次请求
let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(query, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    clearSelection()
    load(0)
  }, 300)
})

/**
 * 键盘导航。只在没有输入焦点、且没按修饰键时接管，
 * 否则会抢走浏览器和输入框的正常行为。
 */
function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  const typing = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable

  if (typing) {
    if (event.key === 'Escape') {
      if (query.value) {
        query.value = ''
        void nextTick(() => searchEl.value?.blur())
      } else {
        searchEl.value?.blur()
      }
    }
    return
  }

  if (event.metaKey || event.ctrlKey || event.altKey) return

  const index = focusedIndex.value

  if (event.key === '/') {
    event.preventDefault()
    searchEl.value?.focus()
    return
  }
  if (event.key === 'j' || event.key === 'ArrowDown') {
    event.preventDefault()
    focusRow(index < 0 ? 0 : index + 1)
    return
  }
  if (event.key === 'k' || event.key === 'ArrowUp') {
    event.preventDefault()
    focusRow(index < 0 ? 0 : index - 1)
    return
  }
  if (event.key === 'Enter' && index >= 0) {
    event.preventDefault()
    const note = notes.value[index]
    if (note) open(note)
    return
  }
  if (event.key === 'x' && index >= 0) {
    event.preventDefault()
    const note = notes.value[index]
    if (note) toggleSelect(note.id)
    return
  }
  if (event.key === 'Escape') {
    clearSelection()
    focusedIndex.value = -1
  }
}

/**
 * 批量发布/下线。发布前先跑预检：
 * 发布是全站唯一不可撤销的动作（下线只是从公开列表移除），
 * 原来的确认框只说「正文为空的会被跳过」，用户看不到选中的 200 篇里混了什么。
 * 现在有阻断项的自动排除，有警告的先摊开给人看，再决定发不发。
 */
const preflight = ref<PreflightReport | null>(null)
const preflightOpen = ref(false)
const preflightRunning = ref(false)

const preflightItems = computed(() => preflight.value?.items.filter((item) => item.findings.length) ?? [])
/** 有阻断项的笔记会被排除，这个数就是「点了发布真会发出去几篇」 */
const publishableCount = computed(() => {
  if (!preflight.value) return 0
  return preflight.value.items.filter((item) => !item.findings.some((f) => f.level === 'BLOCK')).length
})

async function batch(action: 'publish' | 'unpublish') {
  const ids = [...selected.value]
  if (!ids.length) return
  const publishing = action === 'publish'

  if (publishing) {
    preflightRunning.value = true
    try {
      preflight.value = await preflightNotes(ids)
    } catch (e) {
      notify(e instanceof Error ? e.message : String(e), 'error')
      return
    } finally {
      preflightRunning.value = false
    }
    if (preflight.value.blockers > 0 || preflight.value.warnings > 0) {
      preflightOpen.value = true
      return
    }
  }
  await runBatch(action, ids)
}

async function runBatch(action: 'publish' | 'unpublish', ids: number[]) {
  const publishing = action === 'publish'
  const ok = await confirmAction({
    title: publishing ? `发布选中的 ${ids.length} 篇？` : `下线选中的 ${ids.length} 篇？`,
    message: publishing ? '发布后会立即出现在博客上。' : '下线后这些文章会从博客上移除，笔记本身保留为草稿。',
    confirmText: publishing ? '发布' : '下线',
    danger: !publishing,
  })
  if (!ok) return

  try {
    const result = publishing ? await publishNotes(ids) : await unpublishNotes(ids)
    notify(
      `已${publishing ? '发布' : '下线'} ${result.succeeded} 篇${result.skipped ? `，跳过 ${result.skipped} 篇` : ''}`,
    )
    if (result.skippedItems.length) {
      const sample = result.skippedItems
        .slice(0, 3)
        .map((item) => `《${item.title ?? item.id}》${item.reason}`)
        .join('；')
      notify(sample, 'error')
    }
    clearSelection()
    load(page.value)
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  }
}

/**
 * 给选中的笔记补摘要与标签。
 *
 * <p>预检会把「没摘要 / 没标签」报成提醒，选一批草稿发布时那些提醒里
 * 一半是这类噪音，真正的风险反而被埋掉。补完再发，提醒才有信息量。
 * 单次后端最多处理 10 篇，超出的提示再来一次。
 */
const metaRunning = ref(false)

async function fillSelectedMeta() {
  const ids = [...selected.value]
  if (!ids.length || metaRunning.value) return
  metaRunning.value = true
  try {
    const report = await generateMetaBatch({ ids, limit: 10 })
    const parts = [`已补全 ${report.generated} 篇`]
    if (report.skipped) parts.push(`跳过 ${report.skipped} 篇（已有摘要和标签）`)
    if (report.failed) parts.push(`失败 ${report.failed} 篇`)
    notify(parts.join('，'), report.failed ? 'error' : 'success')
    if (report.failed && report.failures[0]) notify(report.failures[0].reason, 'error')
    if (report.hasMore) notify('一次最多处理 10 篇，还剩一些，可再点一次', 'error')
    load(page.value)
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    metaRunning.value = false
  }
}

/** 预检弹窗里点「发布」：只发没有阻断项的那部分 */
function confirmPreflight() {
  const ids = (preflight.value?.items ?? [])
    .filter((item) => !item.findings.some((f) => f.level === 'BLOCK'))
    .map((item) => item.id)
  preflightOpen.value = false
  if (!ids.length) {
    notify('选中的笔记都有阻断项，没有可发布的', 'error')
    return
  }
  runBatch('publish', ids)
}

async function togglePublish(note: NoteSummary) {
  try {
    const updated = note.status === 'PUBLISHED' ? await unpublishNote(note.id) : await publishNote(note.id)
    note.status = updated.status
    notify(updated.status === 'PUBLISHED' ? '已发布，博客中可以看到' : '已转为草稿')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  }
}

async function remove(note: NoteSummary) {
  const ok = await confirmAction({
    title: `删除「${note.title}」？`,
    message: '删除后无法恢复，相关的语义索引也会一并删除。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteNote(note.id)
    clearSelection()
    notify('已删除')
    load(page.value)
    loadFolders()
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  }
}

watch(
  () => route.query.ask,
  (val) => {
    if (val === '1' || val === 'open') {
      openDrawer()
    }
  },
  { immediate: true },
)

onMounted(() => {
  // ?path= 进来的（分享链接、刷新、从详情页返回）先恢复选中项，再拉数据
  const initial = typeof route.query.path === 'string' ? route.query.path : ''
  if (initial) {
    activePath.value = initial
    revealFolder(initial)
  }
  load(0)
  loadFolders()
  loadTags()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <section class="page library">
    <header class="page-head">
      <div>
        <h1 class="page-title">笔记</h1>
        <p class="page-note">
          <template v-if="loading">正在整理书架…</template>
          <template v-else-if="filtering">筛选出 {{ totalElements }} 篇</template>
          <template v-else>共 {{ totalElements }} 篇</template>
          <span v-if="!loading && totalPages > 1"> · 第 {{ page + 1 }} / {{ totalPages }} 页</span>
        </p>
      </div>
      <div class="head-actions">
        <span class="keys"><kbd>/</kbd> 搜索 <kbd>j</kbd><kbd>k</kbd> 移动 <kbd>x</kbd> 选中</span>
        <button
          class="btn btn-ghost"
          type="button"
          title="打开 AI 问答助手 (Cmd+J / Ctrl+J)"
          @click="openDrawer(query || undefined)"
        >
          <span class="sparkle" aria-hidden="true">✦</span> 问答助手
        </button>
        <RouterLink class="btn btn-primary" to="/editor/new">写一篇</RouterLink>
      </div>
    </header>

    <div class="layout">
      <aside class="libraries" aria-label="按目录筛选">
        <button class="lib" :class="{ 'lib-active': activePath === '' }" type="button" @click="pickFolder('')">
          <span class="lib-name">全部</span>
          <span class="lib-count">{{ allNotes }}</span>
        </button>

        <FolderTree :nodes="folders" :selected="activePath" @select="pickFolder" />

        <div v-if="tags.length" class="shelf">
          <p class="shelf-title">
            分类
            <button v-if="activeTags.length" class="tag-clear" type="button" @click="clearTags">清除</button>
          </p>
          <div class="tag-cloud">
            <button
              v-for="item in visibleTags"
              :key="item.name"
              class="tag-chip"
              :class="{ 'tag-on': activeTags.includes(item.name) }"
              type="button"
              :title="`${item.name}（${item.count} 篇）`"
              @click="toggleTag(item.name)"
            >
              {{ item.name }}<span class="tag-num">{{ item.count }}</span>
            </button>
          </div>
          <button v-if="tags.length > TAG_LIMIT" class="tag-more" type="button" @click="tagsExpanded = !tagsExpanded">
            {{ tagsExpanded ? '收起' : `更多 ${tags.length - TAG_LIMIT} 个` }}
          </button>
        </div>
      </aside>

      <div class="list">
        <div class="toolbar">
          <div class="search-wrap">
            <svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" />
              <path d="M16 16l4.5 4.5" />
            </svg>
            <input
              ref="searchEl"
              v-model="query"
              class="field search"
              type="search"
              placeholder="搜索标题或标签"
              aria-label="搜索标题或标签"
            />
            <button v-if="query" class="search-clear" type="button" aria-label="清空搜索" @click="query = ''">×</button>
          </div>
          <div class="segments" role="group" aria-label="状态筛选">
            <button type="button" :class="{ on: status === 'ALL' }" @click="pickStatus('ALL')">全部</button>
            <button type="button" :class="{ on: status === 'PUBLISHED' }" @click="pickStatus('PUBLISHED')">
              已发布
            </button>
            <button type="button" :class="{ on: status === 'DRAFT' }" @click="pickStatus('DRAFT')">草稿</button>
          </div>
          <label v-if="notes.length" class="select-all">
            <input type="checkbox" :checked="allVisibleSelected" @change="toggleSelectAllVisible" />
            全选本页
          </label>
        </div>

        <Transition name="bulk">
          <div v-if="selectionCount" class="bulk">
            <span class="bulk-count">已选 {{ selectionCount }} 篇</span>
            <button class="btn btn-primary btn-sm" type="button" :disabled="preflightRunning" @click="batch('publish')">
              {{ preflightRunning ? '检查中…' : '发布' }}
            </button>
            <button class="btn btn-ghost btn-sm" type="button" @click="batch('unpublish')">下线</button>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="metaRunning" @click="fillSelectedMeta">
              {{ metaRunning ? '补全中…' : '补全摘要' }}
            </button>
            <button class="btn btn-text" type="button" @click="clearSelection">取消选择</button>
          </div>
        </Transition>

        <p v-if="error" class="error-text">{{ error }}</p>
        <LoadingRows v-else-if="loading" :rows="10" variant="row" />

        <div v-else-if="!notes.length" class="empty-state">
          <p class="empty-title">{{ filtering ? '没有符合条件的笔记' : '还没有笔记' }}</p>
          <p class="empty-hint">
            {{ filtering ? '换个关键词，或把筛选切回「全部」。' : '从写好一篇开始，它会自动切块进入检索。' }}
          </p>
          <div class="empty-actions">
            <button v-if="filtering" class="btn btn-ghost btn-sm" type="button" @click="resetFilters">清除筛选</button>
            <RouterLink v-else class="btn btn-primary btn-sm" to="/editor/new">写第一篇</RouterLink>
          </div>
          <div v-if="query.trim()" class="empty-ai-box">
            <button class="btn btn-ghost btn-sm btn-empty-ai" type="button" @click="openDrawer(query, true)">
              <span class="sparkle">✦</span> 用 AI 问答在全库 695 篇笔记中检索「{{ query }}」
            </button>
          </div>
        </div>

        <div v-else ref="listEl" class="rows">
          <div
            v-for="(note, index) in notes"
            :key="note.id"
            class="row rise"
            :class="{
              'row-focused': focusedIndex === index,
              'row-picked': selected.has(note.id),
            }"
            :style="{ '--i': Math.min(index, 12) }"
            @click="pickAndOpen(note, index)"
          >
            <input
              class="row-check"
              type="checkbox"
              :checked="selected.has(note.id)"
              :aria-label="`选择 ${note.title}`"
              @click.stop
              @change="toggleSelect(note.id)"
            />
            <div class="row-main">
              <RouterLink class="row-title" :to="`/notes/${note.slug}`" @click.stop>{{ note.title }}</RouterLink>
              <!-- 摘要：列表里判断「这篇能不能公开」最需要的信息。
                   没有它就得点进正文看，批量筛选的成本全耗在这一步上 -->
              <p v-if="note.summary" class="row-summary">{{ note.summary }}</p>
              <div class="row-meta">
                <span class="folder">{{ folderOf(note) }}</span>
                <span class="dot">·</span>
                <span>{{ dateOf(note.updatedAt) }}</span>
                <span v-for="tag in note.tags.slice(0, 2)" :key="tag" class="tag">#{{ tag }}</span>
              </div>
            </div>
            <div class="row-side">
              <span
                class="state"
                :class="{
                  'state-live': note.status === 'PUBLISHED' && !note.hasUnpublishedEdits,
                  'state-pending': note.hasUnpublishedEdits,
                }"
                :title="note.hasUnpublishedEdits ? '已发布后又改过，线上仍是旧内容。重新发布后才会更新' : undefined"
              >
                {{ note.hasUnpublishedEdits ? '待重发' : note.status === 'PUBLISHED' ? '已发布' : '草稿' }}
              </span>
              <div class="row-actions" @click.stop>
                <RouterLink class="btn btn-text" :to="`/editor/${note.slug}`">编辑</RouterLink>
                <button class="btn btn-text" type="button" @click="togglePublish(note)">
                  {{ note.status === 'PUBLISHED' ? '下线' : '发布' }}
                </button>
                <button class="btn btn-text btn-danger-text" type="button" @click="remove(note)">删除</button>
              </div>
            </div>
          </div>
        </div>

        <nav v-if="totalPages > 1" class="pager" aria-label="翻页">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasPrev" @click="go(page - 1)">上一页</button>
          <span class="pager-info">第 {{ page + 1 }} / {{ totalPages }} 页</span>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasNext" @click="go(page + 1)">下一页</button>
        </nav>
      </div>
    </div>

    <Transition name="pf">
      <div v-if="preflightOpen && preflight" class="pf-scrim" @click.self="preflightOpen = false">
        <div class="pf-sheet" role="dialog" aria-modal="true" aria-labelledby="pf-title">
          <header class="pf-head">
            <h2 id="pf-title" class="pf-title">发布前检查</h2>
            <p class="pf-sum">
              选中 {{ preflight.items.length }} 篇 ·
              <span :class="{ 'pf-bad': preflight.blockers }">阻断 {{ preflight.blockers }}</span> ·
              <span :class="{ 'pf-warn-text': preflight.warnings }">提醒 {{ preflight.warnings }}</span>
            </p>
          </header>

          <ul class="pf-list">
            <li
              v-for="item in preflightItems"
              :key="item.id"
              class="pf-item"
              :class="{ 'is-block': item.findings.some((f) => f.level === 'BLOCK') }"
            >
              <p class="pf-name">{{ item.title }}</p>
              <ul class="pf-findings">
                <li
                  v-for="finding in item.findings"
                  :key="finding.code"
                  :class="finding.level === 'BLOCK' ? 'is-block' : 'is-warn'"
                >
                  {{ finding.message }}
                </li>
              </ul>
            </li>
          </ul>

          <p class="pf-hint">有阻断项的笔记会被自动排除；提醒项不影响发布，发出去之前请确认一遍内容。</p>

          <div class="pf-actions">
            <button class="btn btn-text" type="button" @click="preflightOpen = false">取消</button>
            <button class="btn btn-primary" type="button" :disabled="!publishableCount" @click="confirmPreflight">
              发布 {{ publishableCount }} 篇
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  gap: var(--space-6);
  align-items: start;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.keys {
  font-size: var(--text-xs);
  color: var(--ink-3);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 分类（标签）区：接在库列表下面，同一列滚 */
.shelf {
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--hairline);
}

.shelf-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
  margin: 0 0 var(--space-2);
  padding: 0 10px;
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  color: var(--ink-3);
}

.tag-clear {
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-size: var(--text-xs);
  color: var(--pen);
  cursor: pointer;
}

.tag-clear:hover {
  text-decoration: underline;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding: 0 8px;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 3px 7px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: none;
  color: var(--ink-2);
  font: inherit;
  font-size: var(--text-xs);
  line-height: 1.5;
  cursor: pointer;
  transition:
    border-color var(--dur-1) var(--ease-soft),
    background-color var(--dur-1) var(--ease-soft),
    color var(--dur-1) var(--ease-soft);
}

.tag-chip:hover {
  border-color: var(--ink-3);
  color: var(--ink);
}

/* 选中：钢笔绿描边 + 淡底，与库列表的"书脊"竖条区分开 */
.tag-on {
  border-color: var(--pen);
  background: var(--pen-soft);
  color: var(--pen);
}

.tag-num {
  font-variant-numeric: tabular-nums;
  color: var(--ink-3);
}

.tag-on .tag-num {
  color: var(--pen);
}

.tag-more {
  margin: var(--space-2) 0 0;
  padding: 2px 10px;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--text-xs);
  color: var(--ink-3);
  cursor: pointer;
}

.tag-more:hover {
  color: var(--pen);
}

.libraries {
  position: sticky;
  top: 80px;
  display: grid;
  gap: 1px;
  max-height: calc(100vh - 120px);
  overflow: auto;
  padding-right: var(--space-2);
}

.lib {
  position: relative;
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius);
  background: none;
  color: var(--ink-2);
  font: inherit;
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--dur-1) var(--ease-soft),
    color var(--dur-1) var(--ease-soft);
}

/* 选中项左侧的竖条：比整块换底色更轻，也更像"书脊" */
.lib::before {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 0;
  width: 2px;
  border-radius: 1px;
  background: var(--pen);
  transform: scaleY(0);
  transition: transform var(--dur-2) var(--ease-out);
}

.lib:hover {
  background: var(--row-hover);
  color: var(--ink);
}

.lib-active {
  background: var(--pen-soft);
  color: var(--pen-ink);
  font-weight: 500;
}

.lib-active::before {
  transform: scaleY(1);
}

.lib-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lib-count {
  flex: none;
  color: var(--ink-3);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

.lib-active .lib-count {
  color: var(--pen);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}

.search-wrap {
  position: relative;
  flex: 1;
  min-width: 200px;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 0;
  width: 15px;
  height: 15px;
  fill: none;
  stroke: var(--ink-3);
  stroke-width: 1.7;
  stroke-linecap: round;
  pointer-events: none;
  transition: stroke var(--dur-1);
}

.search-wrap:focus-within .search-icon {
  stroke: var(--pen);
}

.search {
  padding-left: 24px;
  padding-right: 24px;
  font-size: var(--text-md);
}

.search::-webkit-search-cancel-button {
  display: none;
}

.search-clear {
  position: absolute;
  right: 0;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: var(--paper-deep);
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: background-color var(--dur-1);
}

.search-clear:hover {
  background: var(--ink-3);
  color: var(--paper);
}

.segments {
  display: flex;
  gap: var(--space-4);
}

.segments button {
  position: relative;
  padding: 2px 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--text-sm);
  color: var(--ink-3);
  cursor: pointer;
  transition: color var(--dur-1);
}

.segments button::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: -2px;
  left: 0;
  height: 1px;
  background: var(--pen);
  transform: scaleX(0);
  transition: transform var(--dur-2) var(--ease-out);
}

.segments button:hover {
  color: var(--ink);
}

.segments button.on {
  color: var(--ink);
}

.segments button.on::after {
  transform: scaleX(1);
}

.select-all {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-3);
  cursor: pointer;
}

.select-all input {
  width: 15px;
  height: 15px;
  accent-color: var(--pen);
  cursor: pointer;
}

/* 批量条吸顶：选中后往下翻也还能操作，不用滚回去 */
.bulk {
  position: sticky;
  top: 56px;
  z-index: 12;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--pen-soft);
  border-radius: 6px;
  background: var(--pen-soft);
  backdrop-filter: blur(8px);
}

.bulk-count {
  margin-right: auto;
  font-size: var(--text-sm);
  color: var(--pen-ink);
  font-variant-numeric: tabular-nums;
}

.bulk-enter-active,
.bulk-leave-active {
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-2) var(--ease-out);
}

.bulk-enter-from,
.bulk-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 全局 .row 是两列，这里多一列给勾选框 */
.row {
  position: relative;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  cursor: pointer;
}

/* 悬浮底色的横向出血：让高亮铺满视觉行宽，而不是只覆盖文字 */
.row::before {
  content: '';
  position: absolute;
  inset: 0 calc(var(--space-4) * -1);
  border-radius: 4px;
  background: var(--row-hover);
  opacity: 0;
  transition: opacity var(--dur-1) var(--ease-soft);
  pointer-events: none;
  z-index: -1;
}

/* 左侧指示条：从中间向两端拉开 */
.row::after {
  content: '';
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: calc(var(--space-4) * -1);
  width: 2px;
  border-radius: 1px;
  background: var(--pen);
  transform: scaleY(0);
  transform-origin: center;
  transition: transform var(--dur-2) var(--ease-out);
  pointer-events: none;
}

.row:hover::before,
.row-focused::before {
  opacity: 1;
}

.row:hover::after,
.row-focused::after {
  transform: scaleY(1);
}

.row-picked::before {
  opacity: 1;
  background: var(--row-active);
}

.row-check {
  width: 15px;
  height: 15px;
  margin-top: 4px;
  accent-color: var(--pen);
  cursor: pointer;
  opacity: 0.55;
  transition: opacity var(--dur-1);
}

.row:hover .row-check,
.row-focused .row-check,
.row-picked .row-check {
  opacity: 1;
}

.row-title {
  display: inline-block;
}

.row-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

/* 最多两行：摘要够判断就停，行高不能吃掉列表的信息密度 */
.row-summary {
  margin: 3px 0 5px;
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.dot {
  color: var(--ink-3);
}

.folder {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 22rem;
}

.tag {
  padding: 0 5px;
  border-radius: 3px;
  background: var(--paper-deep);
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.6;
}

.row:hover .tag {
  background: var(--pen-soft);
  color: var(--pen);
}

.row-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
}

/* 行内操作平时压暗，鼠标进到这一行才提亮：一屏 40 行不会全是按钮 */
.row-actions {
  display: flex;
  gap: var(--space-4);
  align-items: center;
  opacity: 0.35;
  transition: opacity var(--dur-2) var(--ease-soft);
}

.row:hover .row-actions,
.row-focused .row-actions,
.row-picked .row-actions {
  opacity: 1;
}

.row-actions .btn {
  height: auto;
  padding: 0;
}

.empty-state {
  padding: var(--space-7) 0;
  text-align: center;
  animation: rise var(--dur-3) var(--ease-out) backwards;
}

.empty-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
  color: var(--ink);
}

.empty-hint {
  margin-top: var(--space-2);
  color: var(--ink-3);
  font-size: var(--text-sm);
}

.empty-actions {
  margin-top: var(--space-5);
  display: flex;
  justify-content: center;
  gap: var(--space-3);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  margin-top: var(--space-6);
}

.pager-info {
  font-size: var(--text-sm);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 960px) {
  .keys {
    display: none;
  }
}

@media (max-width: 760px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .libraries {
    position: static;
    grid-auto-flow: column;
    grid-auto-columns: max-content;
    max-height: none;
    padding-bottom: var(--space-2);
    overflow-x: auto;
  }

  .lib::before {
    top: auto;
    bottom: 0;
    left: 8px;
    right: 8px;
    width: auto;
    height: 2px;
    transform: scaleX(0);
  }

  .lib-active::before {
    transform: scaleX(1);
  }

  .row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .row-side {
    grid-column: 1 / -1;
    align-items: flex-start;
    flex-direction: row;
    justify-content: space-between;
    width: 100%;
  }

  .row-actions {
    opacity: 1;
  }

  .folder {
    max-width: 12rem;
  }
}

.empty-ai-box {
  margin-top: var(--space-4);
}

.btn-empty-ai {
  color: var(--pen);
  border-color: var(--pen-soft);
  background: var(--surface);
}

.btn-empty-ai:hover {
  background: var(--pen-soft);
  color: var(--pen-ink);
  border-color: var(--pen);
}

.sparkle {
  color: var(--pen);
  font-size: 13px;
  margin-right: 4px;
}

/* ───────── 发布前检查弹窗 ───────── */
.pf-scrim {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: grid;
  place-items: center;
  padding: var(--space-5);
  background: var(--scrim);
  backdrop-filter: blur(2px);
}

.pf-sheet {
  display: flex;
  flex-direction: column;
  width: min(560px, 100%);
  max-height: min(78vh, 640px);
  padding: var(--space-5);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--lift-3);
}

.pf-head {
  margin-bottom: var(--space-3);
}

.pf-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
}

.pf-sum {
  margin-top: 6px;
  font-size: var(--text-sm);
  color: var(--ink-3);
}

.pf-bad {
  color: var(--danger);
}

.pf-warn-text {
  color: var(--ink);
}

.pf-list {
  flex: 1;
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.pf-item {
  padding: 10px 12px;
  border-left: 2px solid var(--highlight);
  border-radius: 0 var(--radius) var(--radius) 0;
  background: var(--paper-deep);
}

.pf-item.is-block {
  border-left-color: var(--danger);
}

.pf-name {
  font-size: var(--text-sm);
  color: var(--ink);
}

.pf-findings {
  display: grid;
  gap: 2px;
  margin: 6px 0 0;
  padding-left: 1.1em;
  font-size: var(--text-xs);
}

.pf-findings .is-block {
  color: var(--danger);
}

.pf-findings .is-warn {
  color: var(--ink-2);
}

.pf-hint {
  margin-top: var(--space-3);
  font-size: var(--text-xs);
  color: var(--ink-3);
  line-height: 1.7;
}

.pf-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.pf-enter-active,
.pf-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.pf-enter-from,
.pf-leave-to {
  opacity: 0;
}
</style>
