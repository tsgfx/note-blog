<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  clearDraft,
  closeQuickCapture,
  content,
  isOpen,
  PRESET_TAGS,
  saving,
  showTitleInput,
  submitQuickCapture,
  tags,
  title,
} from '../lib/quickCaptureState'

const router = useRouter()
const textareaRef = ref<HTMLTextAreaElement>()
const newTagInput = ref('')
const addingTag = ref(false)

const customTags = computed(() => tags.value.filter((t) => !PRESET_TAGS.includes(t)))

watch(isOpen, async (open) => {
  if (open) {
    await nextTick()
    textareaRef.value?.focus()
  }
})

function onKeydown(event: KeyboardEvent) {
  if (!isOpen.value) return
  if (event.key === 'Escape') {
    closeQuickCapture()
  } else if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    void handleSave()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function toggleTag(tag: string) {
  const idx = tags.value.indexOf(tag)
  if (idx >= 0) {
    if (tags.value.length > 1) {
      tags.value.splice(idx, 1)
    }
  } else {
    tags.value.push(tag)
  }
}

function handleAddCustomTag() {
  const val = newTagInput.value.trim().replace(/^#/, '')
  if (val && !tags.value.includes(val)) {
    tags.value.push(val)
  }
  newTagInput.value = ''
  addingTag.value = false
}

async function handleSave() {
  await submitQuickCapture({ router, navigateToEdit: false })
}

async function handleSaveAndEdit() {
  await submitQuickCapture({ router, navigateToEdit: true })
}
</script>

<template>
  <Teleport to="body">
    <Transition name="capture-anim">
      <div v-if="isOpen" class="capture-scrim" @click.self="closeQuickCapture">
        <div class="capture-dialog" role="dialog" aria-modal="true" aria-labelledby="capture-title">
          <header class="capture-head">
            <div class="capture-title-group">
              <span class="capture-icon" aria-hidden="true">⚡</span>
              <h2 id="capture-title" class="capture-title">记闪念</h2>
              <span class="capture-subtitle">随时捕捉灵感，默认存为草稿</span>
            </div>
            <div class="capture-head-actions">
              <button
                v-if="content.trim() || title.trim()"
                class="capture-btn-text"
                type="button"
                title="清空当前内容"
                @click="clearDraft"
              >
                清空
              </button>
              <button class="capture-close-btn" type="button" aria-label="关闭" @click="closeQuickCapture">✕</button>
            </div>
          </header>

          <div class="capture-body">
            <!-- 可选标题 -->
            <div v-if="showTitleInput" class="capture-title-wrap">
              <input
                v-model="title"
                class="capture-title-input"
                type="text"
                placeholder="标题（留空自动使用第一行或时间戳）"
                maxlength="100"
              />
            </div>

            <!-- 正文多行输入 -->
            <textarea
              ref="textareaRef"
              v-model="content"
              class="capture-textarea"
              placeholder="记录突发奇想、待整理资料或代码片段…&#10;无需担心格式，随时记下"
              rows="6"
            ></textarea>

            <!-- 快捷标签区 -->
            <div class="capture-tags-row">
              <span class="capture-tags-label">标签：</span>
              <div class="capture-tag-chips">
                <button
                  v-for="preset in PRESET_TAGS"
                  :key="preset"
                  type="button"
                  class="tag-chip"
                  :class="{ 'tag-chip-active': tags.includes(preset) }"
                  @click="toggleTag(preset)"
                >
                  #{{ preset }}
                </button>
                <button
                  v-for="custom in customTags"
                  :key="custom"
                  type="button"
                  class="tag-chip tag-chip-active"
                  @click="toggleTag(custom)"
                >
                  #{{ custom }} ✕
                </button>
                <div v-if="addingTag" class="tag-chip-input-wrap">
                  <input
                    v-model="newTagInput"
                    class="tag-chip-input"
                    type="text"
                    placeholder="新标签"
                    autofocus
                    @keydown.enter.prevent="handleAddCustomTag"
                    @blur="handleAddCustomTag"
                  />
                </div>
                <button v-else type="button" class="tag-chip tag-chip-add" @click="addingTag = true">+ 标签</button>
              </div>
            </div>
          </div>

          <footer class="capture-footer">
            <div class="capture-footer-left">
              <button v-if="!showTitleInput" type="button" class="capture-btn-text" @click="showTitleInput = true">
                + 自定义标题
              </button>
              <span v-else class="capture-meta-text">{{ content.length }} 字</span>
              <span class="shortcut-tip">Cmd+Enter 保存</span>
            </div>
            <div class="capture-footer-right">
              <button
                type="button"
                class="btn btn-ghost btn-sm"
                :disabled="saving || !content.trim()"
                @click="handleSaveAndEdit"
              >
                去编辑 ↗
              </button>
              <button
                type="button"
                class="btn btn-primary btn-sm"
                :disabled="saving || !content.trim()"
                @click="handleSave"
              >
                {{ saving ? '保存中…' : '保存闪念' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.capture-scrim {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: var(--space-4);
  background: var(--scrim);
  backdrop-filter: blur(4px);
}

.capture-dialog {
  width: min(540px, 100%);
  background: var(--paper);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-md, 10px);
  box-shadow: var(--lift-3);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-3) var(--ease-out);
}

.capture-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--hairline);
  background: var(--paper-warm, var(--paper));
}

.capture-title-group {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.capture-icon {
  font-size: 16px;
  color: var(--pen);
}

.capture-title {
  font-family: var(--font-serif);
  font-size: var(--text-base);
  font-weight: 600;
  margin: 0;
  color: var(--ink);
}

.capture-subtitle {
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.capture-head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.capture-close-btn {
  background: none;
  border: none;
  color: var(--ink-3);
  font-size: 14px;
  cursor: pointer;
  padding: 4px;
  line-height: 1;
  border-radius: 4px;
  transition: color var(--dur-1) var(--ease-soft);
}

.capture-close-btn:hover {
  color: var(--ink);
}

.capture-body {
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.capture-title-wrap {
  width: 100%;
}

.capture-title-input {
  width: 100%;
  padding: 8px 12px;
  font-size: var(--text-sm);
  font-weight: 500;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm, 6px);
  background: var(--paper);
  color: var(--ink);
  outline: none;
  transition: border-color var(--dur-1) var(--ease-soft);
}

.capture-title-input:focus {
  border-color: var(--pen);
}

.capture-textarea {
  width: 100%;
  padding: 10px 12px;
  font-size: var(--text-sm);
  line-height: 1.6;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-sm, 6px);
  background: var(--paper);
  color: var(--ink);
  resize: vertical;
  min-height: 120px;
  max-height: 380px;
  outline: none;
  font-family: inherit;
  transition: border-color var(--dur-1) var(--ease-soft);
}

.capture-textarea:focus {
  border-color: var(--pen);
}

.capture-tags-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.capture-tags-label {
  font-size: var(--text-xs);
  color: var(--ink-3);
  white-space: nowrap;
}

.capture-tag-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tag-chip {
  background: none;
  border: 1px solid var(--hairline);
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  color: var(--ink-2);
  cursor: pointer;
  transition: all var(--dur-1) var(--ease-soft);
}

.tag-chip:hover {
  border-color: var(--pen);
  color: var(--pen);
}

.tag-chip-active {
  background: var(--pen-soft, rgba(46, 117, 182, 0.1));
  border-color: var(--pen);
  color: var(--pen-ink, var(--pen));
  font-weight: 500;
}

.tag-chip-add {
  border-style: dashed;
  color: var(--ink-3);
}

.tag-chip-input-wrap {
  display: inline-flex;
}

.tag-chip-input {
  width: 72px;
  padding: 2px 6px;
  font-size: 11px;
  border: 1px solid var(--pen);
  border-radius: 12px;
  background: var(--paper);
  color: var(--ink);
  outline: none;
}

.capture-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--hairline);
  background: var(--paper-warm, var(--paper));
}

.capture-footer-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.capture-footer-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.capture-btn-text {
  background: none;
  border: none;
  padding: 0;
  font-size: var(--text-xs);
  color: var(--pen);
  cursor: pointer;
}

.capture-btn-text:hover {
  text-decoration: underline;
}

.capture-meta-text {
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.shortcut-tip {
  font-size: 11px;
  color: var(--ink-3);
}

/* ───────── 动画 ───────── */
.capture-anim-enter-active,
.capture-anim-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.capture-anim-enter-from,
.capture-anim-leave-to {
  opacity: 0;
}

.capture-anim-enter-from .capture-dialog {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

.capture-anim-leave-to .capture-dialog {
  transform: translateY(4px) scale(0.99);
}

/* ───────── 移动端底部抽屉优化 ───────── */
@media (max-width: 640px) {
  .capture-scrim {
    align-items: flex-end;
    padding: 0;
  }

  .capture-dialog {
    width: 100%;
    max-width: 100%;
    border-bottom: none;
    border-radius: 16px 16px 0 0;
    max-height: 85vh;
  }

  .capture-subtitle {
    display: none;
  }

  .shortcut-tip {
    display: none;
  }

  .capture-anim-enter-from .capture-dialog {
    transform: translateY(100%);
  }

  .capture-anim-leave-to .capture-dialog {
    transform: translateY(100%);
  }

  .capture-footer {
    padding-bottom: max(var(--space-3), env(safe-area-inset-bottom, 16px));
  }
}
</style>
