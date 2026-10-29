<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { deleteComment, listAllComments, setCommentVisibility, type AdminComment } from '../api/comments'
import LoadingRows from '../components/LoadingRows.vue'
import { notify } from '../lib/feedback'

const comments = ref<AdminComment[]>([])
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const error = ref('')
/** 正在处理中的行 id：按钮禁用，避免重复点 */
const busyId = ref<number | null>(null)

function formatTime(value: string): string {
  if (!value) return ''
  const [date, time] = value.split('T')
  return `${date} ${(time ?? '').slice(0, 5)}`
}

async function load(target = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await listAllComments(target, 50)
    comments.value = result.content
    page.value = result.number
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function toggle(item: AdminComment) {
  busyId.value = item.id
  try {
    const next = item.status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
    const updated = await setCommentVisibility(item.id, next)
    // 就地替换，避免整页重排导致滚动位置跳走
    const index = comments.value.findIndex((c) => c.id === item.id)
    if (index >= 0 && updated) comments.value[index] = updated
    notify(next === 'HIDDEN' ? '已隐藏' : '已恢复可见')
  } catch (e) {
    notify(e instanceof Error ? e.message : '操作失败', 'error')
  } finally {
    busyId.value = null
  }
}

async function remove(item: AdminComment) {
  // 删除不可撤销，二次确认
  const ok = window.confirm(`确定永久删除这条评论？删除后无法恢复。\n\n${item.content.slice(0, 80)}`)
  if (!ok) return
  busyId.value = item.id
  try {
    await deleteComment(item.id)
    notify('已删除')
    await load(page.value)
  } catch (e) {
    notify(e instanceof Error ? e.message : '删除失败', 'error')
  } finally {
    busyId.value = null
  }
}

onMounted(() => load(0))
</script>

<template>
  <section class="page comments-admin">
    <header class="page-head">
      <div>
        <h1 class="page-title">评论管理</h1>
        <p class="page-note">
          游客的评论默认公开可见。隐藏只是从公开页撤下（内容保留，可随时恢复），删除则不可撤销。共
          {{ totalElements }} 条。
        </p>
      </div>
    </header>

    <p v-if="error" class="error-text">{{ error }}</p>
    <LoadingRows v-else-if="loading" :rows="8" variant="row" />
    <p v-else-if="!comments.length" class="empty">还没有评论。</p>

    <ol v-else class="comment-rows">
      <li
        v-for="item in comments"
        :key="item.id"
        class="comment-row"
        :class="{ 'is-hidden': item.status === 'HIDDEN' }"
      >
        <div class="row-head">
          <span class="row-author">{{ item.author }}</span>
          <span v-if="item.status === 'HIDDEN'" class="badge-hidden">已隐藏</span>
          <time class="row-time" :datetime="item.createdAt">{{ formatTime(item.createdAt) }}</time>
          <span v-if="item.clientIp" class="row-ip" title="仅管理员可见，用于追溯来源">{{ item.clientIp }}</span>
        </div>

        <p class="row-body">{{ item.content }}</p>

        <div class="row-foot">
          <RouterLink
            v-if="item.noteSlug"
            class="row-note"
            :to="`/blog/${item.noteSlug}`"
            target="_blank"
            rel="noopener"
          >
            {{ item.noteTitle || item.noteSlug }}
          </RouterLink>
          <span v-else class="row-note">文章已不存在</span>

          <span class="row-actions">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="busyId === item.id" @click="toggle(item)">
              {{ item.status === 'VISIBLE' ? '隐藏' : '恢复可见' }}
            </button>
            <button
              class="btn btn-ghost btn-sm btn-danger"
              type="button"
              :disabled="busyId === item.id"
              @click="remove(item)"
            >
              删除
            </button>
          </span>
        </div>
      </li>
    </ol>

    <footer v-if="totalPages > 1" class="pager">
      <button class="btn btn-ghost btn-sm" type="button" :disabled="page === 0 || loading" @click="load(page - 1)">
        上一页
      </button>
      <span class="muted">第 {{ page + 1 }} / {{ totalPages }} 页</span>
      <button
        class="btn btn-ghost btn-sm"
        type="button"
        :disabled="page + 1 >= totalPages || loading"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </footer>
  </section>
</template>

<style scoped>
.comments-admin {
  max-width: 920px;
}

.comment-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  list-style: none;
}

.comment-row {
  padding: var(--space-4);
  background: var(--paper);
  border: 1px solid var(--rule);
  border-radius: var(--radius-2);
}

/* 已隐藏的行降饱和：一眼能看出「这条不在公开页」 */
.is-hidden {
  opacity: 0.66;
  border-style: dashed;
}

.row-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.row-author {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--ink);
}

.badge-hidden {
  padding: 0 var(--space-1);
  font-size: 0.72rem;
  color: var(--warn, #a4622a);
  border: 1px solid currentColor;
  border-radius: var(--radius-1);
}

.row-time {
  font-size: 0.78rem;
  color: var(--ink-faint);
}

.row-ip {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.75rem;
  color: var(--ink-faint);
}

.row-body {
  font-size: 0.92rem;
  line-height: 1.7;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}

.row-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.row-note {
  font-size: 0.8rem;
  color: var(--ink-faint);
  text-decoration: none;
}

a.row-note:hover {
  color: var(--pen);
}

.row-actions {
  display: flex;
  gap: var(--space-2);
}

.btn-danger {
  color: var(--danger, #a33);
}

.btn-danger:hover {
  border-color: var(--danger, #a33);
}

@media (max-width: 640px) {
  .row-foot {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
