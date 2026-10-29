<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { isLoggedIn, logout } from './api/auth'
import Toaster from './components/Toaster.vue'
import ConfirmHost from './components/ConfirmHost.vue'
import AskDrawer from './components/AskDrawer.vue'
import { isOpen, toggleDrawer } from './lib/askState'
import { theme, toggleTheme } from './lib/theme'

const route = useRoute()
const router = useRouter()

// 依赖当前路由重新计算，登录/退出后切换即时生效
const signedIn = computed(() => {
  void route.fullPath
  return isLoggedIn()
})

/** 顶栏在滚动后才出现分隔线与投影，静止时保持无边界，阅读页更安静 */
const scrolled = ref(false)

function onScroll() {
  scrolled.value = window.scrollY > 6
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && (event.key === 'j' || event.key === 'J')) {
    if (signedIn.value) {
      event.preventDefault()
      toggleDrawer()
    }
  }
}

onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onGlobalKeydown)
})

function signOut() {
  logout()
  // 退出后回到公开首页，而不是停在博客列表
  router.push('/')
}
</script>

<template>
  <div class="app" :class="{ 'ask-open': isOpen }">
    <header class="topbar" :data-scrolled="scrolled">
      <RouterLink class="mark" to="/">
        <svg class="mark-glyph" viewBox="0 0 32 32" aria-hidden="true">
          <g fill="none" stroke="currentColor" stroke-width="2.8" stroke-linejoin="miter">
            <path d="M6.2 26V6h19.6v11.2" />
            <path d="M18.4 26H6.2" />
            <path d="M11.2 15.4h9.6" stroke-width="3.4" />
          </g>
          <circle cx="21.4" cy="21.2" r="1.7" fill="currentColor" />
        </svg>
        <span>一隅</span>
      </RouterLink>

      <nav v-if="signedIn" class="nav" aria-label="主导航">
        <RouterLink to="/notes">笔记</RouterLink>
        <RouterLink to="/blog">博客</RouterLink>
      </nav>
      <nav v-else class="nav" aria-label="主导航">
        <RouterLink to="/blog">博客</RouterLink>
      </nav>

      <div class="topbar-actions">
        <template v-if="signedIn">
          <button
            class="btn btn-ghost btn-sm btn-ask-trigger"
            type="button"
            :class="{ 'btn-ask-active': isOpen }"
            title="问你的笔记 (Cmd+J / Ctrl+J)"
            @click="toggleDrawer"
          >
            <span class="sparkle" aria-hidden="true">✦</span> 问答
          </button>
          <RouterLink class="btn btn-ghost btn-sm" to="/editor/new">写笔记</RouterLink>
          <RouterLink class="btn-text-link" to="/admin/audit">操作日志</RouterLink>
          <RouterLink class="btn-text-link" to="/settings">设置</RouterLink>
          <button
            class="btn-text-link icon-link"
            type="button"
            :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="toggleTheme"
          >
            <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true" class="glyph">
              <circle cx="12" cy="12" r="4.2" />
              <path
                d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.4 5.4l1.6 1.6M17 17l1.6 1.6M18.6 5.4L17 7M7 17l-1.6 1.6"
              />
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true" class="glyph">
              <path d="M20.5 14.2A8.6 8.6 0 0 1 9.8 3.5a8.6 8.6 0 1 0 10.7 10.7Z" />
            </svg>
          </button>
          <button class="btn-text-link" type="button" @click="signOut">退出</button>
        </template>
        <template v-else>
          <button
            class="btn-text-link icon-link"
            type="button"
            :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="toggleTheme"
          >
            <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true" class="glyph">
              <circle cx="12" cy="12" r="4.2" />
              <path
                d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.4 5.4l1.6 1.6M17 17l1.6 1.6M18.6 5.4L17 7M7 17l-1.6 1.6"
              />
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true" class="glyph">
              <path d="M20.5 14.2A8.6 8.6 0 0 1 9.8 3.5a8.6 8.6 0 1 0 10.7 10.7Z" />
            </svg>
          </button>
          <RouterLink class="btn-text-link" to="/login">管理员登录</RouterLink>
        </template>
      </div>
    </header>

    <main class="main">
      <RouterView v-slot="{ Component, route: current }">
        <Transition name="page" mode="out-in">
          <component :is="Component" :key="current.path" />
        </Transition>
      </RouterView>
    </main>

    <Toaster />
    <ConfirmHost />
    <AskDrawer />
  </div>
</template>

<style scoped>
.btn-ask-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--pen);
  border-color: var(--pen-soft);
  background: var(--paper);
  font-weight: 500;
  transition: all var(--dur-1) var(--ease-soft);
}

.btn-ask-trigger:hover,
.btn-ask-trigger.btn-ask-active {
  background: var(--pen-soft);
  color: var(--pen-ink);
  border-color: var(--pen);
}

.sparkle {
  font-size: 13px;
  color: var(--pen);
}

.btn-text-link {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--text-sm);
  color: var(--ink-3);
  cursor: pointer;
  white-space: nowrap;
  transition: color var(--dur-1) var(--ease-soft);
}

.btn-text-link:hover {
  color: var(--pen);
  text-decoration: none;
}

.icon-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
}

.glyph {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.topbar-actions {
  gap: var(--space-5);
}
</style>
