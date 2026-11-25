<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { parseAccounts, toCsv, toRawLine, type ParsedRecord } from '../lib/accountParser'
import { generateTotp, parseOtpAuthUri, secondsRemaining, type OtpAuthParams } from '../lib/totp'
import { writeClipboard } from '../lib/codecopy'

/**
 * 账号凭据整理。全部在浏览器里算：不请求后端接口，不写 localStorage / sessionStorage，
 * 也不进 URL。唯一的网络请求是路由守卫查了一次「我是不是管理员」。
 */

const rawText = ref('')
const parseError = ref('')
const records = ref<ParsedRecord[]>([])
const reveal = ref(false)
const copiedLabel = ref('')
const now = ref(Date.now())

const codeCache = new Map<string, { slot: number; code: string }>()
const codes = ref<Record<number, string>>({})

let parseTimer = 0
let ticker = 0

const FIELD_LABELS: Record<string, string> = {
  account: '账号',
  password: '密码',
  recoveryEmail: '辅助邮箱',
  code2fa: '2FA 验证码',
  totpSecret: 'TOTP 密钥',
}

function parse() {
  parseError.value = ''
  if (!rawText.value.trim()) {
    records.value = []
    codes.value = {}
    return
  }
  try {
    records.value = parseAccounts(rawText.value)
    codes.value = {}
    codeCache.clear()
    void refreshCodes()
  } catch {
    // 刻意不带原文：错误信息会被 errorGuard 记进控制台，凭据不能跟着进去
    records.value = []
    parseError.value = '解析失败：这段文本的结构无法识别。请逐条粘贴，或换一种分隔方式。'
  }
}

function scheduleParse() {
  window.clearTimeout(parseTimer)
  parseTimer = window.setTimeout(parse, 120)
}

function paramsOf(record: ParsedRecord): OtpAuthParams | null {
  if (record.totpUri) {
    const parsed = parseOtpAuthUri(record.totpUri)
    if (parsed) return parsed
  }
  if (!record.totpSecret) return null
  return {
    type: 'totp',
    secret: record.totpSecret,
    digits: 6,
    period: 30,
    algorithm: 'SHA-1',
    issuer: '',
    account: '',
  }
}

async function refreshCodes() {
  const next: Record<number, string> = {}
  for (let index = 0; index < records.value.length; index += 1) {
    const record = records.value[index]
    const params = paramsOf(record)
    if (!params || params.type === 'hotp') continue
    const slot = Math.floor(Date.now() / 1000 / params.period)
    const cacheKey = `${params.secret}|${params.digits}|${params.period}|${params.algorithm}`
    const cached = codeCache.get(cacheKey)
    if (cached && cached.slot === slot) {
      next[index] = cached.code
      continue
    }
    const code = await generateTotp(params.secret, params)
    if (code) {
      codeCache.set(cacheKey, { slot, code })
      next[index] = code
    }
  }
  codes.value = next
}

function secondsLeft(record: ParsedRecord): number {
  const params = paramsOf(record)
  return secondsRemaining(params?.period ?? 30)
}

const jsonText = computed(() =>
  records.value.length
    ? JSON.stringify(
        records.value.map((record) => ({
          account: record.account,
          password: record.password,
          recoveryEmail: record.recoveryEmail,
          code2fa: record.code2fa,
          totpSecret: record.totpSecret,
        })),
        null,
        2,
      )
    : '',
)

const csvText = computed(() => (records.value.length ? toCsv(records.value) : ''))
const rawLines = computed(() => records.value.map(toRawLine).join('\n'))

/** 面板里默认打码；真值只从数据模型取，复制出去才还原。 */
function mask(text: string): string {
  if (reveal.value) return text
  return text.replace(/[^\s:,{}[\]"-]/g, '•')
}

const maskedJson = computed(() => mask(jsonText.value))
const maskedCsv = computed(() => mask(csvText.value))
const maskedRaw = computed(() => mask(rawLines.value))

async function copy(text: string, label: string) {
  const ok = await writeClipboard(text)
  copiedLabel.value = ok ? `${label} 已复制` : `${label} 复制失败`
  window.setTimeout(() => {
    if (copiedLabel.value === `${label} 已复制` || copiedLabel.value === `${label} 复制失败`) copiedLabel.value = ''
  }, 1800)
}

function copyField(record: ParsedRecord, key: keyof ParsedRecord) {
  const value = String(record[key] ?? '')
  if (!value) return
  void copy(value, FIELD_LABELS[key as string] ?? String(key))
}

function clearAll() {
  rawText.value = ''
  records.value = []
  codes.value = {}
  parseError.value = ''
  codeCache.clear()
  copiedLabel.value = ''
}

function onPageShow(event: PageTransitionEvent) {
  // 从 bfcache 回来时页面状态是原样恢复的：主动清掉，别把凭据留在屏幕上
  if (event.persisted) clearAll()
}

watch(rawText, scheduleParse)

onMounted(() => {
  ticker = window.setInterval(() => {
    const previous = Math.floor(now.value / 1000)
    now.value = Date.now()
    const current = Math.floor(now.value / 1000)
    if (current !== previous) void refreshCodes()
  }, 1000)
  window.addEventListener('pageshow', onPageShow)
})

onBeforeUnmount(() => {
  window.clearInterval(ticker)
  window.clearTimeout(parseTimer)
  window.removeEventListener('pageshow', onPageShow)
  clearAll()
})
</script>

<template>
  <section class="page accounts">
    <header class="page-head">
      <div>
        <h1 class="page-title">账号整理</h1>
        <p class="page-note">
          把账号、密码、辅助邮箱、2FA
          混排的文本贴进下面，自动拆成规整字段。全部在本机浏览器里处理，不上传、不保存；关掉页面即清空。
        </p>
      </div>
    </header>

    <div class="acc-input">
      <label class="acc-label" for="account-raw-input">原始文本</label>
      <textarea
        id="account-raw-input"
        v-model="rawText"
        class="acc-textarea"
        rows="6"
        placeholder="支持：账号----密码----辅助邮箱----2FA / 账号：a@b.com 密码是 … / 一行 JSON / otpauth:// 链接"
        autocomplete="off"
        autocorrect="off"
        autocapitalize="off"
        spellcheck="false"
        data-1p-ignore
        data-lpignore="true"
        data-form-type="other"
      />
      <div class="acc-input-actions">
        <span class="acc-hint">共 {{ records.length }} 条</span>
        <span v-if="copiedLabel" class="acc-copied">{{ copiedLabel }}</span>
        <button type="button" class="btn btn-ghost btn-sm" @click="clearAll">清空</button>
      </div>
      <p v-if="parseError" class="error-text">{{ parseError }}</p>
    </div>

    <p v-if="rawText && !records.length && !parseError" class="empty">没有识别出完整的账号条目。</p>

    <div v-if="records.length" class="acc-grid">
      <article v-for="(record, index) in records" :key="index" class="acc-card">
        <header class="acc-card-head">
          <span class="acc-card-index">#{{ index + 1 }}</span>
          <button
            type="button"
            class="btn btn-ghost btn-sm"
            :aria-label="reveal ? '隐藏密码' : '显示密码'"
            @click="reveal = !reveal"
          >
            {{ reveal ? '隐藏' : '显示' }}
          </button>
        </header>

        <div
          v-for="field in ['account', 'password', 'recoveryEmail', 'code2fa', 'totpSecret'] as const"
          :key="field"
          class="acc-field"
        >
          <div class="acc-field-head">
            <label class="acc-field-label" :for="`acc-${index}-${field}`">{{ FIELD_LABELS[field] }}</label>
            <span v-if="record.fields[field] && record.fields[field]!.confidence !== 'high'" class="acc-field-flag"
              >启发式推断</span
            >
            <button
              v-if="record[field]"
              type="button"
              class="acc-field-copy"
              :title="`复制${FIELD_LABELS[field]}`"
              @click="copyField(record, field)"
            >
              复制
            </button>
          </div>
          <input
            :id="`acc-${index}-${field}`"
            v-model="record[field]"
            class="acc-field-input"
            :type="field === 'password' && !reveal ? 'password' : 'text'"
            autocomplete="off"
            spellcheck="false"
            data-1p-ignore
            data-lpignore="true"
            data-form-type="other"
          />
        </div>

        <div v-if="codes[index]" class="acc-totp">
          <span class="acc-totp-code">{{ codes[index] }}</span>
          <span class="acc-totp-hint">
            {{ secondsLeft(record) }} 秒后刷新 · {{ paramsOf(record)?.period ?? 30 }} 秒一换
            <template v-if="paramsOf(record)?.digits !== 6"> · {{ paramsOf(record)?.digits }} 位</template>
          </span>
        </div>

        <p v-if="record.note" class="acc-note">{{ record.note }}</p>
      </article>
    </div>

    <div v-if="records.length" class="acc-outputs">
      <section class="acc-output">
        <header class="acc-output-head">
          <h2 class="acc-output-title">原始行</h2>
          <button type="button" class="btn btn-ghost btn-sm" @click="copy(rawLines, '原始行')">复制全部</button>
        </header>
        <pre class="acc-pre">{{ maskedRaw }}</pre>
      </section>

      <section class="acc-output">
        <header class="acc-output-head">
          <h2 class="acc-output-title">JSON</h2>
          <button type="button" class="btn btn-ghost btn-sm" @click="copy(jsonText, 'JSON')">复制全部</button>
        </header>
        <pre class="acc-pre">{{ maskedJson }}</pre>
      </section>

      <section class="acc-output">
        <header class="acc-output-head">
          <h2 class="acc-output-title">CSV</h2>
          <button type="button" class="btn btn-ghost btn-sm" @click="copy(csvText, 'CSV')">复制全部</button>
        </header>
        <pre class="acc-pre">{{ maskedCsv }}</pre>
      </section>
    </div>
  </section>
</template>

<style scoped>
.acc-input {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
}

.acc-label {
  font-size: var(--text-sm);
  color: var(--ink-2);
}

.acc-textarea {
  width: 100%;
  min-height: 8rem;
  padding: var(--space-3);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  line-height: 1.6;
  resize: vertical;
}

.acc-input-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
}

.acc-hint,
.acc-copied {
  margin-right: auto;
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.acc-card-index {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.acc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.acc-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--paper);
  overflow-wrap: anywhere;
}

.acc-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.acc-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.acc-field-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.acc-field-label {
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.acc-field-flag {
  font-size: var(--text-xs);
  color: var(--ink-3);
  border: 1px dashed var(--hairline);
  border-radius: 8px;
  padding: 0 4px;
}

.acc-field-copy {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: var(--pen);
  font-size: var(--text-xs);
  cursor: pointer;
}

.acc-field-input {
  width: 100%;
  min-width: 0;
  padding: 4px 6px;
  border: 1px solid var(--hairline);
  border-radius: 6px;
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
}

.acc-totp {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  padding: 4px 0 0;
  border-top: 1px dashed var(--hairline);
}

.acc-totp-code {
  font-family: var(--font-mono);
  font-size: var(--text-lg);
  letter-spacing: 0.08em;
}

.acc-totp-hint,
.acc-note {
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.acc-outputs {
  display: grid;
  gap: var(--space-4);
}

.acc-output {
  border: 1px solid var(--hairline);
  border-radius: var(--radius);
  background: var(--paper);
  padding: var(--space-3);
}

.acc-output-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.acc-output-title {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--ink-2);
}

.acc-pre {
  margin: 0;
  max-height: 18rem;
  overflow: auto;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .acc-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
