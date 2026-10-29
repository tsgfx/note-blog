<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listAuditLogs, type AuditLog } from '../api/audit'
import LoadingRows from '../components/LoadingRows.vue'

const ACTIONS: Record<string, string> = {
  LOGIN: '登录',
  LOGIN_FAILED: '登录失败',
  LOGIN_BLOCKED: '登录被锁',
  NOTE_CREATE: '新建笔记',
  NOTE_UPDATE: '编辑笔记',
  NOTE_DELETE: '删除笔记',
  NOTE_PUBLISH: '发布笔记',
  NOTE_UNPUBLISH: '下线笔记',
  NOTE_BATCH_PUBLISH: '批量发布',
  NOTE_BATCH_UNPUBLISH: '批量下线',
  ASSET_UPLOAD: '上传图片',
  PASSWORD_CHANGE: '修改密码',
}

const logs = ref<AuditLog[]>([])
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const error = ref('')

function labelOf(action: string): string {
  return ACTIONS[action] ?? action
}

function timeOf(value: string): { date: string; time: string } {
  const [date = '', time = ''] = value.split('T')
  return { date, time }
}

function subjectOf(log: AuditLog): string {
  return log.detail || log.target || ''
}

/**
 * 按天分组。日志只按时间倒序排列时，找"那天干了什么"要一行行数日期；
 * 加一条日期分隔就一目了然。分组只针对当前页，不改变服务端分页语义。
 */
const groups = computed(() => {
  const map = new Map<string, AuditLog[]>()
  logs.value.forEach((log) => {
    const day = timeOf(log.createdAt).date || '未知日期'
    const bucket = map.get(day)
    if (bucket) bucket.push(log)
    else map.set(day, [log])
  })
  return [...map.entries()].map(([day, items]) => ({ day, items }))
})

async function load(next = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await listAuditLogs(next)
    logs.value = result.content
    page.value = result.number
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => load(0))
</script>

<template>
  <section class="page audit">
    <header class="page-head">
      <div>
        <h1 class="page-title">操作日志</h1>
        <p class="page-note">记录登录、笔记的增删改与发布、图片上传。按时间倒序排列，共 {{ totalElements }} 条。</p>
      </div>
    </header>

    <p v-if="error" class="error-text">{{ error }}</p>
    <LoadingRows v-else-if="loading" :rows="10" variant="row" />
    <p v-else-if="!logs.length" class="empty">还没有操作记录。</p>

    <div v-else class="log-groups">
      <section v-for="group in groups" :key="group.day" class="log-group">
        <h2 class="log-day">{{ group.day }}</h2>
        <ol class="log-list">
          <li
            v-for="(log, index) in group.items"
            :key="log.id"
            class="log rise"
            :class="{ 'log-failed': log.action === 'LOGIN_FAILED' }"
            :style="{ '--i': Math.min(index, 10) }"
          >
            <time class="log-time" :datetime="log.createdAt">
              <span>{{ timeOf(log.createdAt).time }}</span>
            </time>
            <span class="log-user">{{ log.username }}</span>
            <p class="log-what">
              <span class="log-action">{{ labelOf(log.action) }}</span>
              <span v-if="subjectOf(log)" class="log-subject">{{ subjectOf(log) }}</span>
            </p>
          </li>
        </ol>
      </section>
    </div>

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
.audit {
  max-width: 920px;
}

.log-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.log-group + .log-group {
  margin-top: var(--space-6);
}

.log-day {
  position: sticky;
  top: 56px;
  z-index: 5;
  margin-bottom: var(--space-1);
  padding: var(--space-2) 0;
  background: var(--paper);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  font-weight: 400;
  color: var(--ink-2);
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}

.log {
  display: grid;
  grid-template-columns: 84px 110px minmax(0, 1fr);
  gap: var(--space-4);
  align-items: baseline;
  padding: var(--space-3) var(--space-3);
  margin: 0 calc(var(--space-3) * -1);
  border-bottom: 1px solid var(--hairline);
  font-size: var(--text-sm);
  transition: background-color var(--dur-1) var(--ease-soft);
}

.log:hover {
  background: var(--row-hover);
}

.log-time {
  display: flex;
  gap: var(--space-2);
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.log-user {
  color: var(--ink-2);
}

.log-what {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  min-width: 0;
}

.log-action {
  color: var(--ink);
  font-weight: 600;
}

.log-subject {
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.log-failed .log-action {
  color: var(--danger);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-5);
  margin-top: var(--space-5);
}

@media (max-width: 640px) {
  .log {
    grid-template-columns: 1fr;
    gap: var(--space-1);
  }
}
</style>
