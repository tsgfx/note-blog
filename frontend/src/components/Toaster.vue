<script setup lang="ts">
import { dismiss, toasts } from '../lib/feedback'
</script>

<template>
  <div class="toaster" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <button
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :class="`toast-${t.kind}`"
        type="button"
        @click="dismiss(t.id)"
      >
        <span class="toast-mark" aria-hidden="true" />
        {{ t.message }}
      </button>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toaster {
  position: fixed;
  right: var(--space-5);
  bottom: var(--space-5);
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
  max-width: 360px;
  padding: var(--space-3) var(--space-4);
  border: 0;
  border-radius: 6px;
  background: var(--toast-bg);
  color: var(--toast-fg);
  font: inherit;
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
  box-shadow: var(--lift-3);
}

.toast-mark {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-3);
}

.toast-success .toast-mark {
  background: var(--ok);
}

.toast-error .toast-mark {
  background: var(--danger);
}

.toast-enter-active {
  transition:
    opacity var(--dur-2) var(--ease-soft),
    transform var(--dur-3) var(--ease-out);
}

.toast-leave-active {
  position: absolute;
  transition:
    opacity var(--dur-1) var(--ease-soft),
    transform var(--dur-1) var(--ease-soft);
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(16px) scale(0.98);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(8px);
}

@media (max-width: 760px) {
  .toaster {
    right: var(--space-4);
    left: var(--space-4);
    bottom: var(--space-4);
    align-items: stretch;
  }

  .toast {
    max-width: none;
  }
}
</style>
