<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <!--
    404 兜底页。之前没有 catch-all 路由，访问不存在的路径会渲染出「顶栏 + 空白」，
    看着像页面挂了。nginx 已有 try_files → index.html 的 SPA 回退，所以这里能兜住。
  -->
  <section class="notfound">
    <svg class="seal" viewBox="0 0 32 32" aria-hidden="true">
      <g fill="none" stroke="currentColor" stroke-width="2.8" stroke-linejoin="miter">
        <path d="M6.2 26V6h19.6v11.2" />
        <path d="M18.4 26H6.2" />
        <path d="M11.2 15.4h9.6" stroke-width="3.4" />
      </g>
      <circle cx="21.4" cy="21.2" r="1.7" fill="currentColor" />
    </svg>

    <p class="code">404</p>
    <h1 class="page-title">这一隅没有东西</h1>
    <p class="page-note">地址可能拼错了，或者这篇笔记被删了、还没公开。要不要回到有字的地方？</p>

    <div class="links">
      <RouterLink class="go" to="/">回首页</RouterLink>
      <RouterLink class="go ghost" to="/blog">看博客</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.notfound {
  max-width: 40rem;
  margin: clamp(2rem, 8vh, 6rem) auto 0;
  text-align: center;
}

.seal {
  width: 56px;
  height: 56px;
  color: var(--pen);
  opacity: 0.5;
}

.code {
  margin-top: var(--space-4);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  letter-spacing: 0.24em;
  color: var(--ink-3);
}

.page-title {
  margin-top: var(--space-2);
}

.page-note {
  max-width: 26rem;
  margin-inline: auto;
  line-height: 1.9;
}

.links {
  display: flex;
  gap: var(--space-3);
  justify-content: center;
  margin-top: var(--space-6);
  flex-wrap: wrap;
}

.go {
  padding: 0.5rem 1.1rem;
  border: 1px solid var(--pen);
  border-radius: 999px;
  color: var(--on-pen);
  background: var(--pen);
  font-size: var(--text-sm);
  text-decoration: none;
  transition:
    transform var(--dur-1) var(--ease-soft),
    background var(--dur-2) var(--ease-soft);
}

.go:hover {
  transform: translateY(-1px);
  background: var(--pen-ink);
}

.ghost {
  color: var(--pen);
  background: transparent;
}

.ghost:hover {
  background: var(--pen-soft);
}
</style>
