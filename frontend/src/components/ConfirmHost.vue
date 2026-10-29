<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { answerConfirm, confirmState } from '../lib/feedback'

const confirmButton = ref<HTMLButtonElement>()

watch(
  () => confirmState.open,
  async (open) => {
    if (!open) return
    await nextTick()
    confirmButton.value?.focus()
  },
)

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && confirmState.open) answerConfirm(false)
}

// 挂在 window 上而不是对话框容器上：焦点意外落在遮罩外时 Esc 仍然有效
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="confirmState.open" class="scrim" @click.self="answerConfirm(false)">
        <div class="dialog" role="alertdialog" aria-modal="true" :aria-label="confirmState.title">
          <h2 class="dialog-title">{{ confirmState.title }}</h2>
          <p v-if="confirmState.message" class="dialog-message">{{ confirmState.message }}</p>
          <div class="dialog-actions">
            <button class="btn btn-ghost" type="button" @click="answerConfirm(false)">
              {{ confirmState.cancelText || '取消' }}
            </button>
            <button
              ref="confirmButton"
              class="btn"
              :class="confirmState.danger ? 'btn-danger' : 'btn-primary'"
              type="button"
              @click="answerConfirm(true)"
            >
              {{ confirmState.confirmText || '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.scrim {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: var(--space-4);
  background: var(--scrim);
  backdrop-filter: blur(2px);
}

.dialog {
  width: min(420px, 100%);
  padding: var(--space-5);
  background: var(--paper);
  border: 1px solid var(--hairline);
  border-radius: 8px;
  box-shadow: var(--lift-3);
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-3) var(--ease-out);
}

.dialog-enter-active,
.dialog-leave-active {
  transition: opacity var(--dur-2) var(--ease-soft);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

/* 遮罩淡入的同时，对话框自己轻微上浮并放大到 100%，两段动效不同步才有纵深 */
.dialog-enter-from .dialog {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

.dialog-leave-to .dialog {
  transform: translateY(4px) scale(0.99);
}

.dialog-title {
  font-family: var(--font-serif);
  font-size: var(--text-h3);
}

.dialog-message {
  margin-top: var(--space-3);
  color: var(--ink-2);
  font-size: var(--text-sm);
  line-height: 1.7;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-5);
}

.btn-danger {
  background: var(--danger);
  color: var(--on-danger);
}

.btn-danger:hover {
  filter: brightness(0.92);
}
</style>
