<script setup lang="ts">
/**
 * 载入骨架。用它替掉"正在读取…"——纯文字加载在 695 篇的库上会让人以为是空页，
 * 而且数据到达时整页跳动。骨架先占好位置，数据到了只替换内容，布局不动。
 */
withDefaults(
  defineProps<{
    rows?: number
    variant?: 'row' | 'post' | 'article'
  }>(),
  { rows: 8, variant: 'row' },
)
</script>

<template>
  <div class="loading" :class="`loading-${variant}`" aria-busy="true" aria-live="polite">
    <span class="sr-only">正在载入</span>

    <template v-if="variant === 'article'">
      <div class="skeleton skeleton-line" style="width: 42%; height: 30px" />
      <div class="skeleton skeleton-line" style="width: 24%; margin-top: 14px" />
      <div class="body">
        <div
          v-for="n in 7"
          :key="n"
          class="skeleton skeleton-line"
          :style="{ width: `${[96, 88, 92, 74, 90, 84, 56][n - 1]}%` }"
        />
      </div>
    </template>

    <template v-else-if="variant === 'post'">
      <div v-for="n in rows" :key="n" class="post">
        <div class="skeleton skeleton-line date" />
        <div class="stack">
          <div class="skeleton skeleton-line" :style="{ width: `${[62, 48, 70, 54][(n - 1) % 4]}%`, height: '18px' }" />
          <div class="skeleton skeleton-line" style="width: 88%" />
        </div>
      </div>
    </template>

    <template v-else>
      <div v-for="n in rows" :key="n" class="row">
        <div class="stack">
          <div
            class="skeleton skeleton-line"
            :style="{ width: `${[54, 38, 62, 46, 58, 34, 50, 44][(n - 1) % 8]}%`, height: '16px' }"
          />
          <div class="skeleton skeleton-line" style="width: 32%; margin-top: 10px" />
        </div>
        <div class="skeleton skeleton-line" style="width: 44px" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.loading {
  border-top: 1px solid var(--hairline);
}

.row,
.post {
  display: grid;
  gap: var(--space-4);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--hairline);
}

.row {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.post {
  grid-template-columns: 112px minmax(0, 1fr);
  gap: var(--space-5);
  padding: var(--space-5) 0;
  align-items: start;
}

.date {
  width: 80px;
  margin-top: 4px;
}

.stack {
  display: grid;
  gap: 0;
}

.body {
  display: grid;
  gap: 16px;
  margin-top: var(--space-6);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

@media (max-width: 640px) {
  .post {
    grid-template-columns: 1fr;
    gap: var(--space-2);
  }
}
</style>
