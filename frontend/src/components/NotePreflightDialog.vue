<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { PreflightReport } from '../api/notes'
import { hasBlockingFinding, publishablePreflightIds, warningPreflightIds } from '../lib/preflight'

const { open, report } = defineProps<{
  open: boolean
  report: PreflightReport | null
}>()

const emit = defineEmits<{
  close: []
  confirm: [ids: number[]]
}>()

/** 本次检查里作者主动勾掉不发布的篇目；每次打开都是一轮新的决策。 */
const excludedIds = ref<Set<number>>(new Set())

const itemsWithFindings = computed(() => report?.items.filter((item) => item.findings.length) ?? [])
const publishableIds = computed(() => (report ? publishablePreflightIds(report, excludedIds.value) : []))
const publishableCount = computed(() => publishableIds.value.length)

function toggleExclude(id: number) {
  const next = new Set(excludedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  excludedIds.value = next
}

/** 一键排除所有带警告的篇目（如含疑似密钥/手机号），保留纯净笔记安心发布 */
function excludeAllWarnings() {
  if (!report) return
  const next = new Set(excludedIds.value)
  warningPreflightIds(report).forEach((id) => next.add(id))
  excludedIds.value = next
}

function close() {
  emit('close')
}

function confirm() {
  emit('confirm', publishableIds.value)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  close()
}

watch(
  () => open,
  (isOpen) => {
    if (isOpen) {
      excludedIds.value = new Set()
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Transition name="pf">
    <div v-if="open && report" class="pf-scrim" @click.self="close">
      <div class="pf-sheet" role="dialog" aria-modal="true" aria-labelledby="pf-title">
        <header class="pf-head">
          <div class="pf-head-row">
            <h2 id="pf-title" class="pf-title">发布前检查</h2>
            <button
              v-if="report.warnings > 0"
              class="btn btn-ghost btn-xs pf-quick-clean"
              type="button"
              @click="excludeAllWarnings"
            >
              排除所有告警篇目
            </button>
          </div>
          <p class="pf-sum">
            选中 {{ report.items.length }} 篇 ·
            <span :class="{ 'pf-bad': report.blockers }">阻断 {{ report.blockers }}</span> ·
            <span :class="{ 'pf-warn-text': report.warnings }">提醒 {{ report.warnings }}</span>
            <span v-if="excludedIds.size" class="pf-excluded"> · 已排除 {{ excludedIds.size }} 篇</span>
          </p>
        </header>

        <ul class="pf-list">
          <li
            v-for="item in itemsWithFindings"
            :key="item.id"
            class="pf-item"
            :class="{
              'is-block': hasBlockingFinding(item),
              'is-excluded': excludedIds.has(item.id),
            }"
          >
            <div class="pf-item-header">
              <label class="pf-item-label">
                <input
                  type="checkbox"
                  :disabled="hasBlockingFinding(item)"
                  :checked="!hasBlockingFinding(item) && !excludedIds.has(item.id)"
                  @change="toggleExclude(item.id)"
                />
                <span class="pf-name">{{ item.title }}</span>
              </label>
              <RouterLink
                class="pf-edit-link"
                :to="{ name: 'editor', params: { slug: item.id } }"
                target="_blank"
                title="在新标签页中打开编辑并脱敏"
              >
                去编辑 ↗
              </RouterLink>
            </div>
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

        <p class="pf-hint">
          有阻断项的笔记会被自动排除；提醒项可手动取消勾选暂缓发布，点击「去编辑」可在新标签页脱敏。
        </p>

        <div class="pf-actions">
          <button class="btn btn-text" type="button" @click="close">取消</button>
          <button class="btn btn-primary" type="button" :disabled="!publishableCount" @click="confirm">
            发布 {{ publishableCount }} 篇
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
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

.pf-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.pf-quick-clean {
  font-size: var(--text-xs);
  padding: 2px 8px;
  color: var(--pen);
  border: 1px solid var(--hairline);
  border-radius: 999px;
  cursor: pointer;
  background: var(--paper);
}

.pf-quick-clean:hover {
  background: var(--paper-deep);
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

.pf-excluded {
  color: var(--pen);
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
  transition: opacity var(--dur-1);
}

.pf-item.is-block {
  border-left-color: var(--danger);
}

.pf-item.is-excluded {
  opacity: 0.55;
  background: var(--paper);
}

.pf-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.pf-item-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  flex: 1;
}

.pf-item-label input {
  accent-color: var(--pen);
  cursor: pointer;
}

.pf-name {
  font-size: var(--text-sm);
  color: var(--ink);
}

.pf-edit-link {
  font-size: var(--text-xs);
  color: var(--pen);
  text-decoration: none;
  padding: 2px 6px;
  border-radius: 4px;
}

.pf-edit-link:hover {
  background: var(--paper-deep);
  text-decoration: underline;
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
