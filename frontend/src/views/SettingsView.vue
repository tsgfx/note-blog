<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, logout } from '../api/auth'
import { generateMetaBatch, reindexMissingNotes } from '../api/notes'
import { getBackupStatus, getHealth, type BackupStatus, type HealthReport } from '../api/system'
import { notify } from '../lib/feedback'

const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const error = ref('')

const backup = ref<BackupStatus | null>(null)
const backupLoading = ref(true)
const backupError = ref('')
const health = ref<HealthReport | null>(null)

/** 依赖的中文名与「坏了会怎样」，比裸的 db / embedding 有用 */
const COMPONENTS: { key: string; label: string; impact: string }[] = [
  { key: 'db', label: '数据库', impact: '读写全部失败' },
  { key: 'embedding', label: '向量模型', impact: '问答与语义检索不可用，浏览编辑不受影响' },
]

/**
 * 备份状态与依赖健康一起拉。
 *
 * <p>加载失败不弹 toast：设置页的主任务是改密码，这些只是附带信息，
 * 用一行静态文字说明即可，不该打断。
 */
async function loadStatus() {
  backupLoading.value = true
  backupError.value = ''
  const [backupResult, healthResult] = await Promise.allSettled([getBackupStatus(), getHealth()])

  if (backupResult.status === 'fulfilled') {
    backup.value = backupResult.value
  } else {
    backup.value = null
    backupError.value = backupResult.reason instanceof Error ? backupResult.reason.message : String(backupResult.reason)
  }
  health.value = healthResult.status === 'fulfilled' ? healthResult.value : null
  backupLoading.value = false
}

onMounted(loadStatus)

/** 相对时间：备份状态看的是「多久以前」，精确到秒没有意义 */
function relative(iso?: string): string {
  if (!iso) return '从未'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return '未知'
  const minutes = Math.floor((Date.now() - then) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

function absolute(iso?: string): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function size(bytes: number): string {
  if (!bytes) return '—'
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// ── 维护：批量补元数据 / 补建索引 ──────────────────────────

const metaRunning = ref(false)
const metaStop = ref(false)
const metaProgress = ref('')
const reindexRunning = ref(false)
const reindexProgress = ref('')

/** 单批后端最多 10 篇；这里传 10 让循环次数最少 */
const META_BATCH = 10
/** 循环上限：按每批 10 篇算，够覆盖 400 篇的全缺口，同时防住死循环 */
const META_MAX_ROUNDS = 40

/**
 * 循环补全，直到全库没有缺口。
 *
 * <p>两个必须的保险：一是 {@code remaining} 没往下走就停——模型偶尔返回缺 summary
 * 的 JSON，那篇会一直缺、一直排在最前，不判收敛就会无限重试白烧额度；
 * 二是允许中途停止，否则一次点下去就是十几分钟关不掉的操作。
 */
async function fillMeta() {
  if (metaRunning.value) return
  metaRunning.value = true
  metaStop.value = false
  metaProgress.value = '正在检查缺口…'
  let total = 0
  let lastRemaining = Number.POSITIVE_INFINITY
  try {
    for (let round = 0; round < META_MAX_ROUNDS; round++) {
      if (metaStop.value) break
      const report = await generateMetaBatch({ limit: META_BATCH })
      total += report.generated
      metaProgress.value = `已补 ${total} 篇 · 剩余 ${report.remaining} 篇`

      if (report.remaining === 0) break
      if (report.remaining >= lastRemaining) {
        notify(`剩余 ${report.remaining} 篇没有减少，已停在原地。多为模型返回内容缺字段，稍后可重试`, 'error')
        break
      }
      if (report.generated === 0 && report.failed > 0) {
        notify(`连续失败：${report.failures[0]?.reason ?? '模型暂时不可用'}`, 'error')
        break
      }
      lastRemaining = report.remaining
    }
    if (total > 0) notify(`已补全 ${total} 篇的摘要与标签`, 'success')
    else if (!metaProgress.value.includes('失败')) notify('没有需要补全的笔记')
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    metaRunning.value = false
    metaStop.value = false
  }
}

async function reindex() {
  if (reindexRunning.value) return
  reindexRunning.value = true
  reindexProgress.value = '正在排队…'
  let total = 0
  try {
    // 线程池队列只有 20 个槽位，整库缺分片（比如刚恢复过备份）必须反复调用。
    // 用 remaining 判断收敛，别让用户自己数点了多少次。
    for (let round = 0; round < 60; round++) {
      const report = await reindexMissingNotes()
      total += report.queued
      reindexProgress.value = `已排队 ${total} 篇 · 仍缺 ${report.remaining} 篇`
      if (report.remaining === 0) break
      // 队列已满、一篇也排不进：等一会儿再试，避免空转打请求
      if (report.queued === 0) await new Promise((resolve) => setTimeout(resolve, 1500))
    }
    notify(
      total > 0 ? `已为 ${total} 篇笔记排队重建索引，后台慢慢跑` : '没有需要补建索引的笔记',
      total > 0 ? 'success' : undefined,
    )
  } catch (e) {
    notify(e instanceof Error ? e.message : String(e), 'error')
  } finally {
    reindexRunning.value = false
  }
}

/**
 * 三条规则实时判定。
 * 只在校验失败后弹一行错误的话，用户得试三次才知道差在哪；
 * 这里每敲一个字就把还差什么显示出来。
 */
const rules = computed(() => [
  { label: '至少 8 位', ok: newPassword.value.length >= 8, touched: newPassword.value.length > 0 },
  {
    label: '不超过 72 字节',
    ok: new TextEncoder().encode(newPassword.value).length <= 72,
    touched: newPassword.value.length > 0,
  },
  {
    label: '与当前密码不同',
    ok: newPassword.value.length > 0 && newPassword.value !== currentPassword.value,
    touched: newPassword.value.length > 0 && currentPassword.value.length > 0,
  },
  {
    label: '两次输入一致',
    ok: confirmPassword.value.length > 0 && confirmPassword.value === newPassword.value,
    touched: confirmPassword.value.length > 0,
  },
])

const allRulesOk = computed(() => rules.value.every((rule) => rule.ok))

/** 前端预校验只是提前反馈，服务端仍会完整校验一遍 */
function validate(): string {
  if (!currentPassword.value) return '请输入当前密码'
  if (newPassword.value.length < 8) return '新密码至少 8 位'
  if (new TextEncoder().encode(newPassword.value).length > 72) return '新密码超过 72 字节，请缩短'
  if (newPassword.value === currentPassword.value) return '新密码不能与当前密码相同'
  if (confirmPassword.value !== newPassword.value) return '两次输入的新密码不一致'
  return ''
}

async function submit() {
  error.value = validate()
  if (error.value) return
  submitting.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    // 服务端已吊销全部旧令牌，本地也必须清掉，否则下一次请求会 401
    logout()
    notify('密码已修改，请用新密码重新登录', 'success')
    router.replace({ name: 'login', query: { reason: 'password-changed' } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="page settings">
    <header class="page-head">
      <div>
        <h1 class="page-title">账号设置</h1>
        <p class="page-note">修改后，所有已登录的设备都会立即失效，需要用新密码重新登录。</p>
      </div>
    </header>

    <form class="settings-form" @submit.prevent="submit">
      <div class="field-block">
        <label class="field-label" for="current-password">当前密码</label>
        <input
          id="current-password"
          v-model="currentPassword"
          class="field"
          type="password"
          autocomplete="current-password"
          autofocus
        />
      </div>

      <div class="field-block">
        <label class="field-label" for="new-password">新密码</label>
        <input id="new-password" v-model="newPassword" class="field" type="password" autocomplete="new-password" />
      </div>

      <div class="field-block">
        <label class="field-label" for="confirm-password">确认新密码</label>
        <input
          id="confirm-password"
          v-model="confirmPassword"
          class="field"
          type="password"
          autocomplete="new-password"
        />
      </div>

      <ul class="rules">
        <li v-for="rule in rules" :key="rule.label" class="rule" :class="{ 'is-ok': rule.ok, 'is-off': !rule.touched }">
          <span class="rule-dot" aria-hidden="true" />
          {{ rule.label }}
        </li>
      </ul>

      <p v-if="error" class="error-text field-gap">{{ error }}</p>

      <button class="btn btn-primary settings-submit" type="submit" :disabled="submitting || !allRulesOk">
        {{ submitting ? '修改中' : '修改密码' }}
      </button>
    </form>

    <section class="backup" aria-labelledby="health-title">
      <header class="backup-head">
        <h2 id="health-title" class="backup-title">运行状态</h2>
      </header>

      <p v-if="!health" class="muted">读不到健康状态。</p>
      <ul v-else class="health-list">
        <li v-for="item in COMPONENTS" :key="item.key" class="health-row">
          <span class="health-dot" :class="{ 'is-bad': !health.components[item.key] }" aria-hidden="true" />
          <span class="health-name">{{ item.label }}</span>
          <span class="health-state" :class="{ 'is-bad': !health.components[item.key] }">
            {{ health.components[item.key] ? '正常' : '不可用' }}
          </span>
          <span v-if="!health.components[item.key]" class="health-impact">{{ item.impact }}</span>
        </li>
      </ul>
      <p v-if="health && health.status === 'degraded'" class="notice notice-warn">
        有依赖不可用。后端进程本身是好的，但这些功能会失败——不需要重启后端，去查对应容器。
      </p>
      <p v-if="health" class="muted health-version">
        版本 <code>{{ health.version || '未知' }}</code> · 检查于 {{ health.time.slice(11, 19) }}
      </p>
    </section>

    <section class="backup" aria-labelledby="backup-title">
      <header class="backup-head">
        <h2 id="backup-title" class="backup-title">备份</h2>
        <button class="btn btn-text btn-sm" type="button" :disabled="backupLoading" @click="loadStatus">
          {{ backupLoading ? '检查中' : '重新检查' }}
        </button>
      </header>

      <p v-if="backupError" class="error-text">{{ backupError }}</p>
      <p v-else-if="backupLoading" class="muted">正在检查备份状态…</p>

      <template v-else-if="backup">
        <p v-if="!backup.available" class="notice notice-warn">
          没有读到备份目录 <code>{{ backup.dir }}</code
          >。容器里它是 <code>./backups</code> 的只读挂载： 确认目录存在，并重建过 backend 容器。
        </p>

        <template v-else>
          <p class="backup-line">
            最近成功备份：<strong>{{ relative(backup.lastSuccessAt) }}</strong>
            <span v-if="backup.lastSuccessAt" class="backup-dim">
              （{{ absolute(backup.lastSuccessAt) }} · 数据库 {{ size(backup.lastSuccessSize) }}）
            </span>
            <span class="backup-dim">· 现存 {{ backup.copies }} 份 · 保留 14 份</span>
          </p>

          <p v-if="backup.stale" class="notice notice-warn">
            已超过 48 小时没有成功备份。检查定时任务是否在跑：<code>launchctl list | grep notesblog</code>， 日志在
            <code>~/Library/Logs/notesblog-backup.log</code>。
          </p>

          <p v-if="backup.lastErrorMessage" class="notice notice-error">
            上次备份失败（{{ relative(backup.lastErrorAt) }}）：{{ backup.lastErrorMessage }}
          </p>

          <p v-if="!backup.stale && !backup.lastErrorMessage" class="notice notice-ok">备份正常。</p>
        </template>
      </template>
    </section>

    <section class="backup" aria-labelledby="maintenance-title">
      <header class="backup-head">
        <h2 id="maintenance-title" class="backup-title">维护</h2>
        <button v-if="metaRunning" class="btn btn-text btn-sm" type="button" @click="metaStop = true">停止</button>
      </header>

      <p class="muted">
        补齐历史笔记缺失的摘要与标签。每批 10 篇、逐篇调用模型，期间不要关闭页面；可随时停止，已完成的不会回退。
      </p>
      <p v-if="metaProgress" class="maint-progress">{{ metaProgress }}</p>
      <button class="btn btn-ghost btn-sm" type="button" :disabled="metaRunning" @click="fillMeta">
        {{ metaRunning ? '正在补全…' : '补全缺失的摘要与标签' }}
      </button>

      <p class="muted maint-gap">
        补建语义索引：嵌入服务曾失败过的笔记会留下「有正文但没有向量块」的状态，列表里看不出异常，问答却永远搜不到。
      </p>
      <p v-if="reindexProgress" class="maint-progress">{{ reindexProgress }}</p>
      <button class="btn btn-ghost btn-sm" type="button" :disabled="reindexRunning" @click="reindex">
        {{ reindexRunning ? '排队中…' : '补建缺失索引' }}
      </button>
    </section>
  </section>
</template>

<style scoped>
.settings {
  max-width: 480px;
}

.settings-form {
  margin-top: var(--space-6);
}

.field-block + .field-block {
  margin-top: var(--space-5);
}

.field-gap {
  margin-top: var(--space-5);
}

/* 规则清单：满足变绿，未触碰的保持灰，正在输错的显红 */
.rules {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  margin: var(--space-4) 0 0;
  padding: 0;
  list-style: none;
}

.rule {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--ink-3);
  transition: color var(--dur-2) var(--ease-soft);
}

.rule-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  border: 1px solid currentColor;
  transition:
    background-color var(--dur-2),
    border-color var(--dur-2);
}

.rule.is-off {
  opacity: 0.55;
}

.rule:not(.is-off):not(.is-ok) {
  color: var(--danger);
}

.rule.is-ok {
  color: var(--pen);
}

.rule.is-ok .rule-dot {
  background: var(--pen);
  border-color: var(--pen);
}

.settings-submit {
  width: 100%;
  height: 40px;
  margin-top: var(--space-6);
}

/* ───────── 备份状态 ───────── */
.backup {
  margin-top: var(--space-7);
  padding-top: var(--space-5);
  border-top: 1px solid var(--hairline);
}

.backup-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
}

.backup-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
  font-weight: 600;
}

.backup-line {
  font-size: var(--text-sm);
  color: var(--ink-2);
  line-height: 1.9;
}

.backup-dim {
  color: var(--ink-3);
}

/* ───────── 维护 ───────── */
.maint-progress {
  margin: var(--space-3) 0 var(--space-3);
  font-size: var(--text-sm);
  font-family: var(--font-mono);
  color: var(--ink-2);
}

.maint-gap {
  margin-top: var(--space-5);
}

/* ───────── 运行状态 ───────── */
.health-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.health-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-3);
  font-size: var(--text-sm);
}

.health-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
  align-self: center;
}

.health-dot.is-bad {
  background: var(--danger);
}

.health-name {
  min-width: 5em;
  color: var(--ink);
}

.health-state {
  color: var(--pen);
  font-variant-numeric: tabular-nums;
}

.health-state.is-bad {
  color: var(--danger);
}

.health-impact {
  color: var(--ink-3);
  font-size: var(--text-xs);
}

/* 「服务器上跑的是哪一版」以前只能 ssh 上去翻镜像时间，这里直接显示短 commit */
.health-version {
  margin-top: var(--space-3);
}

.health-version code {
  font-family: var(--font-mono);
  font-size: 0.94em;
  color: var(--ink-2);
}

/* 三种状态共用一种块：底色区分严重程度，不引入新颜色 */
.notice {
  margin-top: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-left: 2px solid var(--ink-3);
  border-radius: 0 var(--radius) var(--radius) 0;
  font-size: var(--text-sm);
  line-height: 1.8;
}

.notice code {
  font-family: var(--font-mono);
  font-size: 0.9em;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--paper-deep);
}

.notice-warn {
  border-left-color: var(--code-number);
  background: var(--highlight);
  color: var(--ink);
}

.notice-error {
  border-left-color: var(--danger);
  color: var(--danger);
  background: transparent;
}

.notice-ok {
  border-left-color: var(--ok);
  color: var(--ink-2);
  background: var(--pen-soft);
}
</style>
