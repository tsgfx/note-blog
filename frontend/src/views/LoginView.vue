<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api/auth'
import { notify } from '../lib/feedback'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const reveal = ref(false)
/** 每次失败自增，用作 key 让抖动动画重新触发 */
const attempt = ref(0)

const passwordType = computed(() => (reveal.value ? 'text' : 'password'))

/** 从登录页跳转过来的原因，决定顶部提示语 */
const hint = computed(() => {
  if (route.query.reason === 'expired') return '登录已过期，请重新登录。'
  if (route.query.reason === 'password-changed') return '密码已修改，请用新密码重新登录。'
  return '登录后可以写笔记、发布文章。'
})

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    attempt.value += 1
    return
  }
  loading.value = true
  try {
    const result = await login(username.value.trim(), password.value)
    localStorage.setItem('notes_blog_token', result.token)
    localStorage.setItem('notes_blog_user', result.displayName || result.username)
    notify(`欢迎回来，${result.displayName || result.username}`, 'success')
    router.replace(String(route.query.redirect || '/ask'))
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    password.value = ''
    attempt.value += 1
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="login">
    <div class="sheet">
      <p class="brand">一隅</p>
      <h1 class="login-title">管理员登录</h1>
      <p class="page-note">{{ hint }}</p>

      <form class="login-form" @submit.prevent="submit">
        <div class="field-group">
          <label class="field-label" for="username">用户名</label>
          <input id="username" v-model="username" class="field" autocomplete="username" autofocus :disabled="loading" />
        </div>

        <div class="field-group">
          <label class="field-label" for="password">密码</label>
          <div class="password-wrap">
            <input
              id="password"
              v-model="password"
              class="field"
              :type="passwordType"
              autocomplete="current-password"
              :disabled="loading"
            />
            <button
              class="reveal"
              type="button"
              :aria-label="reveal ? '隐藏密码' : '显示密码'"
              :title="reveal ? '隐藏密码' : '显示密码'"
              @click="reveal = !reveal"
            >
              {{ reveal ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <p v-if="error" :key="attempt" class="error-text login-error">{{ error }}</p>

        <button class="btn btn-primary login-submit" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner" aria-hidden="true" />
          {{ loading ? '登录中' : '登录' }}
        </button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: calc(100vh - 56px);
  padding: var(--space-5);
}

.sheet {
  width: min(380px, 100%);
  padding: var(--space-6) var(--space-5) var(--space-5);
  border: 1px solid var(--hairline);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--lift-2);
  animation: rise var(--dur-3) var(--ease-out) backwards;
}

.brand {
  font-family: var(--font-serif);
  font-size: var(--text-xs);
  letter-spacing: 0.16em;
  color: var(--ink-3);
  margin-bottom: var(--space-4);
}

.login-title {
  font-family: var(--font-serif);
  font-size: var(--text-h2);
}

.login-form {
  margin-top: var(--space-6);
}

.field-group + .field-group {
  margin-top: var(--space-5);
}

.field-group {
  animation: rise var(--dur-3) var(--ease-out) backwards;
}

.field-group:nth-child(1) {
  animation-delay: 40ms;
}

.field-group:nth-child(2) {
  animation-delay: 80ms;
}

.password-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.password-wrap .field {
  padding-right: 46px;
}

.reveal {
  position: absolute;
  right: 0;
  padding: 2px 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--text-xs);
  color: var(--ink-3);
  cursor: pointer;
  transition: color var(--dur-1);
}

.reveal:hover {
  color: var(--pen);
}

.login-error {
  margin-top: var(--space-4);
  animation: shake 420ms var(--ease-soft);
}

.login-submit {
  width: 100%;
  margin-top: var(--space-6);
  height: 40px;
  gap: var(--space-2);
}

.spinner {
  width: 12px;
  height: 12px;
  border: 1.6px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 密码错的时候抖一下：视觉上明确"这次没进去"，不用读文字也知道 */
@keyframes shake {
  0%,
  100% {
    transform: translateX(0);
  }
  20% {
    transform: translateX(-4px);
  }
  40% {
    transform: translateX(4px);
  }
  60% {
    transform: translateX(-2px);
  }
  80% {
    transform: translateX(2px);
  }
}
</style>
