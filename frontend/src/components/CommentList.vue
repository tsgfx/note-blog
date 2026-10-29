<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createComment, listComments, type Comment } from '../api/comments'

const props = defineProps<{ slug: string }>()

const comments = ref<Comment[]>([])
const total = ref(0)
const page = ref(0)
const totalPages = ref(0)
const loading = ref(true)
const error = ref('')

const content = ref('')
/**
 * 蜜罐：正常用户看不见，机器人常把每个 input 都填一遍。
 * 用文本框而不是 checkbox——勾选框需要「主动点」才算命中，
 * 而机器人填表单时通常只管 text 类字段。
 */
const honeypot = ref('')
const submitting = ref(false)
const submitError = ref('')
const justPosted = ref(false)

const MAX_LEN = 2000
const remainingChars = computed(() => MAX_LEN - content.value.length)
const canSubmit = computed(() => content.value.trim().length > 0 && !submitting.value)

function formatTime(value: string): string {
  if (!value) return ''
  // 后端返回 2026-10-29T13:05:00，直接取到分钟够用；不引日期库
  const [date, time] = value.split('T')
  return `${date} ${(time ?? '').slice(0, 5)}`
}

async function load(target = 0) {
  loading.value = true
  error.value = ''
  try {
    const result = await listComments(props.slug, target, 20)
    comments.value = result.content
    total.value = result.totalElements
    page.value = result.number
    totalPages.value = result.totalPages
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  submitError.value = ''
  justPosted.value = false
  try {
    await createComment(props.slug, content.value.trim(), honeypot.value.trim().length > 0)
    content.value = ''
    honeypot.value = ''
    // 蜜罐命中时后端回 null 但仍算成功——不区分，避免告诉机器人「你被识别了」
    justPosted.value = true
    await load(0)
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : String(e)
  } finally {
    submitting.value = false
  }
}

function go(target: number) {
  load(target)
}

onMounted(() => load(0))
</script>

<template>
  <section class="comments" aria-label="评论">
    <header class="comments-head">
      <h2 class="comments-title">
        评论<span v-if="total" class="comments-count">{{ total }}</span>
      </h2>
    </header>

    <form class="comment-form" @submit.prevent="submit">
      <label class="sr-only" for="comment-body">评论内容</label>
      <textarea
        id="comment-body"
        v-model="content"
        class="comment-input"
        rows="3"
        :maxlength="MAX_LEN"
        placeholder="说点什么…（不填昵称，显示为「访客」）"
      />
      <!-- 蜜罐：对正常用户不可见、不可聚焦，机器人会填 -->
      <div class="honeypot" aria-hidden="true">
        <label for="comment-website">网址（请留空）</label>
        <input id="comment-website" v-model="honeypot" type="text" tabindex="-1" autocomplete="off" />
      </div>
      <div class="comment-actions">
        <span v-if="remainingChars < 200" class="char-count">还可输入 {{ remainingChars }} 字</span>
        <span v-else class="form-hint">评论默认公开，管理员可隐藏或删除</span>
        <button class="btn btn-sm" type="submit" :disabled="!canSubmit">
          {{ submitting ? '发送中…' : '发表评论' }}
        </button>
      </div>
      <p v-if="submitError" class="form-error">{{ submitError }}</p>
      <p v-if="justPosted" class="form-ok">已发表</p>
    </form>

    <p v-if="loading" class="comments-note">加载中…</p>
    <p v-else-if="error" class="comments-note">{{ error }}</p>
    <p v-else-if="!comments.length" class="comments-note">还没有评论。第一条留给你。</p>

    <ol v-else class="comment-list">
      <li v-for="item in comments" :key="item.id" class="comment-item">
        <div class="comment-meta">
          <span class="comment-author">{{ item.author }}</span>
          <time class="comment-time">{{ formatTime(item.createdAt) }}</time>
        </div>
        <p class="comment-body">{{ item.content }}</p>
      </li>
    </ol>

    <nav v-if="totalPages > 1" class="comment-pager" aria-label="评论翻页">
      <button class="btn btn-sm btn-ghost" type="button" :disabled="page <= 0" @click="go(page - 1)">上一页</button>
      <span class="pager-note">{{ page + 1 }} / {{ totalPages }}</span>
      <button class="btn btn-sm btn-ghost" type="button" :disabled="page + 1 >= totalPages" @click="go(page + 1)">
        下一页
      </button>
    </nav>
  </section>
</template>

<style scoped>
.comments {
  margin-top: var(--space-8);
  padding-top: var(--space-6);
  border-top: 1px solid var(--rule);
}

.comments-head {
  margin-bottom: var(--space-4);
}

.comments-title {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--ink);
}

.comments-count {
  margin-left: var(--space-2);
  font-size: 0.85rem;
  font-weight: 400;
  color: var(--ink-faint);
}

.comment-form {
  margin-bottom: var(--space-6);
}

.comment-input {
  width: 100%;
  padding: var(--space-3);
  font: inherit;
  font-size: 0.95rem;
  line-height: 1.7;
  color: var(--ink);
  background: var(--paper);
  border: 1px solid var(--rule);
  border-radius: var(--radius-2);
  resize: vertical;
  transition: border-color var(--dur-1) var(--ease-soft);
}

.comment-input:focus {
  outline: none;
  border-color: var(--pen-soft);
}

/* 蜜罐：挪出视口而不是 display:none —— 有些爬虫会跳过 display:none 的字段 */
.honeypot {
  position: absolute;
  left: -9999px;
  width: 1px;
  height: 1px;
  overflow: hidden;
}

.comment-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.form-hint,
.char-count {
  font-size: 0.82rem;
  color: var(--ink-faint);
}

.char-count {
  color: var(--warn, #a4622a);
}

.form-error {
  margin-top: var(--space-2);
  font-size: 0.85rem;
  color: var(--danger, #a33);
}

.form-ok {
  margin-top: var(--space-2);
  font-size: 0.85rem;
  color: var(--ok, #2f7a4f);
}

.comments-note {
  padding: var(--space-4) 0;
  font-size: 0.9rem;
  color: var(--ink-faint);
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  list-style: none;
}

.comment-item {
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--rule-faint, var(--rule));
}

.comment-item:last-child {
  border-bottom: none;
}

.comment-meta {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

.comment-author {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--ink);
}

.comment-time {
  font-size: 0.78rem;
  color: var(--ink-faint);
}

.comment-body {
  font-size: 0.94rem;
  line-height: 1.75;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-5);
}

.pager-note {
  font-size: 0.82rem;
  color: var(--ink-faint);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 640px) {
  .comment-actions {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
