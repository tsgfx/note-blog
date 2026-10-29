<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getBlogStats, listPublishedNotes, type BlogStats, type NoteSummary } from '../api/notes'
import { isLoggedIn } from '../api/auth'
import { profile } from '../lib/profile'

const stats = ref<BlogStats>({ published: 0, tags: 0 })
const posts = ref<NoteSummary[]>([])
const libraryReady = ref(false)

const signedIn = isLoggedIn()
const nameChars = computed(() => [...profile.name])
const reduceMotion = typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/* ───────── 数字滚动 ─────────
   首屏的四个指标和笔记库的篇数都走同一套缓出逻辑：
   前段快、末段收，读起来像「落定」而不是匀速跳。 */
const metricShown = reactive(profile.highlights.map(() => 0))
const counts = reactive({ published: 0, tags: 0 })

function countTo(target: number, decimals: number, assign: (value: number) => void) {
  if (reduceMotion) {
    assign(target)
    return
  }
  const duration = 1100
  const start = performance.now()
  const step = (now: number) => {
    const progress = Math.min(1, (now - start) / duration)
    const eased = 1 - Math.pow(1 - progress, 3)
    assign(Number((target * eased).toFixed(decimals)))
    if (progress < 1) window.requestAnimationFrame(step)
  }
  window.requestAnimationFrame(step)
}

/* ───────── 鼠标光斑（首屏）───────── */
const heroEl = ref<HTMLElement>()
let spotFrame = 0
let spotPoint = { x: 0, y: 0 }

function onPointerMove(event: PointerEvent) {
  if (reduceMotion || !heroEl.value) return
  spotPoint = { x: event.clientX, y: event.clientY }
  if (spotFrame) return
  spotFrame = window.requestAnimationFrame(() => {
    spotFrame = 0
    const el = heroEl.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    el.style.setProperty('--mx', `${spotPoint.x - rect.left}px`)
    el.style.setProperty('--my', `${spotPoint.y - rect.top}px`)
  })
}

/* ───────── 首屏视差 ─────────
   内容比页面滚得慢一点，墨晕留在原处，层次就出来了。幅度压得很小（≤70px），
   大了会晕。只改 transform，不碰布局属性。 */
const heroShift = ref(0)
let heroFrame = 0

function onScroll() {
  if (reduceMotion || heroFrame) return
  heroFrame = window.requestAnimationFrame(() => {
    heroFrame = 0
    heroShift.value = Math.min(window.scrollY * 0.16, 70)
  })
}

/* ───────── 卡片倾斜 ─────────
   幅度故意压得很小（≤5°）：大角度会像玩具，小角度只是让卡面「有厚度」。
   和光斑一样用 rAF 合并，避免每次 pointermove 都写四条自定义属性。 */
let tiltFrame = 0
let tiltEl: HTMLElement | null = null
let tiltRatio = { x: 0.5, y: 0.5 }

function tilt(event: PointerEvent) {
  if (reduceMotion) return
  // 触屏上 pointermove 是滚动手势的一部分，跟着触发倾斜只会抖动——只留给鼠标
  if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return
  const el = event.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  tiltEl = el
  tiltRatio = {
    x: (event.clientX - rect.left) / rect.width,
    y: (event.clientY - rect.top) / rect.height,
  }
  if (tiltFrame) return
  tiltFrame = window.requestAnimationFrame(() => {
    tiltFrame = 0
    if (!tiltEl) return
    tiltEl.style.setProperty('--rx', `${((0.5 - tiltRatio.y) * 4).toFixed(2)}deg`)
    tiltEl.style.setProperty('--ry', `${((tiltRatio.x - 0.5) * 6).toFixed(2)}deg`)
    tiltEl.style.setProperty('--gx', `${(tiltRatio.x * 100).toFixed(1)}%`)
    tiltEl.style.setProperty('--gy', `${(tiltRatio.y * 100).toFixed(1)}%`)
  })
}

function untilt(event: PointerEvent) {
  const el = event.currentTarget as HTMLElement
  el.style.setProperty('--rx', '0deg')
  el.style.setProperty('--ry', '0deg')
  el.style.setProperty('--gx', '50%')
  el.style.setProperty('--gy', '50%')
}

/* ───────── 进场观察 ───────── */
let revealObserver: IntersectionObserver | null = null
let metricObserver: IntersectionObserver | null = null
let libraryObserver: IntersectionObserver | null = null
const metricsEl = ref<HTMLElement>()
const libraryEl = ref<HTMLElement>()

/** 重新扫描 .reveal。数据是异步来的，新渲染的节点必须补观察，否则永远停在透明态 */
async function observeReveals() {
  await nextTick()
  const nodes = document.querySelectorAll<HTMLElement>('.home .reveal:not(.is-in)')
  if (!revealObserver) {
    nodes.forEach((el) => el.classList.add('is-in'))
    return
  }
  nodes.forEach((el) => revealObserver?.observe(el))
}

function runMetrics() {
  profile.highlights.forEach((item, index) => {
    window.setTimeout(() => countTo(item.value, item.decimals, (value) => (metricShown[index] = value)), index * 90)
  })
}

function runLibrary() {
  countTo(stats.value.published, 0, (value) => (counts.published = value))
  countTo(stats.value.tags, 0, (value) => (counts.tags = value))
}

onMounted(async () => {
  if ('IntersectionObserver' in window) {
    revealObserver = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) entry.target.classList.add('is-in')
        })
      },
      { rootMargin: '-6% 0px -10% 0px' },
    )
    // 数字露出来才开始跳，否则用户在首屏看不到它们动
    metricObserver = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return
        runMetrics()
        metricObserver?.disconnect()
      },
      { threshold: 0.35 },
    )
    libraryObserver = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return
        runLibrary()
        libraryObserver?.disconnect()
      },
      { threshold: 0.3 },
    )
    if (metricsEl.value) metricObserver.observe(metricsEl.value)
    if (libraryEl.value) libraryObserver.observe(libraryEl.value)
  } else {
    runMetrics()
    runLibrary()
  }

  if (!reduceMotion) {
    window.addEventListener('pointermove', onPointerMove, { passive: true })
    window.addEventListener('scroll', onScroll, { passive: true })
  }
  await observeReveals()

  // 两个请求互不依赖，并行发出：串行会让首页白等一个 RTT。
  // 用 allSettled 而不是 all，是为了让其中一个挂了另一个仍能正常显示。
  const [statsResult, postsResult] = await Promise.allSettled([getBlogStats(), listPublishedNotes(0, 4)])

  if (statsResult.status === 'fulfilled') {
    stats.value = statsResult.value
    // 观察器可能已经在数据到达前触发过一次（那时全是 0），这里补一次
    if (reduceMotion) runLibrary()
  }
  posts.value = postsResult.status === 'fulfilled' ? postsResult.value.content : []
  libraryReady.value = true
  await observeReveals()
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
  metricObserver?.disconnect()
  libraryObserver?.disconnect()
  if (spotFrame) window.cancelAnimationFrame(spotFrame)
  if (tiltFrame) window.cancelAnimationFrame(tiltFrame)
  if (heroFrame) window.cancelAnimationFrame(heroFrame)
  if (!reduceMotion) {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('scroll', onScroll)
  }
})

function monthDay(value?: string): string {
  return value ? value.slice(5, 10).replace('-', ' / ') : ''
}

/** 外链与 mailto 新开窗口；/api 下的地址交给浏览器直连；其余走路由 */
function linkKind(href?: string): 'none' | 'external' | 'raw' | 'internal' {
  if (!href) return 'none'
  if (/^(https?:|mailto:)/i.test(href)) return 'external'
  if (href.startsWith('/api')) return 'raw'
  return 'internal'
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
}
</script>

<template>
  <div class="home">
    <!-- ───────── 首屏 ───────── -->
    <section ref="heroEl" class="hero">
      <div class="grid-veil" aria-hidden="true" />
      <div class="grain" aria-hidden="true" />
      <div class="aurora" aria-hidden="true">
        <i class="blob-a" />
        <i class="blob-b" />
      </div>
      <div class="spot" aria-hidden="true" />

      <div class="hero-grid" :style="{ transform: `translate3d(0, ${heroShift}px, 0)` }">
        <div class="hero-main">
          <p class="eyebrow">
            <span class="pulse-dot" aria-hidden="true" />
            <span>{{ profile.status }}</span>
            <span class="eyebrow-sep" aria-hidden="true">·</span>
            <span class="eyebrow-place">{{ profile.location }}</span>
          </p>

          <h1 class="name" :aria-label="profile.name">
            <span class="name-wrap">
              <span class="name-line">
                <span
                  v-for="(ch, index) in nameChars"
                  :key="index"
                  class="char"
                  aria-hidden="true"
                  :style="{ '--c': index }"
                >
                  <i>{{ ch }}</i>
                </span>
              </span>
              <svg class="stroke" viewBox="0 0 260 14" preserveAspectRatio="none" aria-hidden="true">
                <path d="M3 9.6C46 4.2 104 2.4 162 4.4 200 5.7 233 8.2 257 7.1" pathLength="1" />
              </svg>
              <span class="ink-drop" aria-hidden="true" />
            </span>
          </h1>

          <p class="latin">
            {{ profile.latin }}<span class="latin-dot" aria-hidden="true">·</span>{{ profile.handle }}
          </p>
          <p class="role">{{ profile.role }}</p>
          <p class="intro">{{ profile.intro }}</p>

          <div class="actions">
            <RouterLink class="btn btn-primary" to="/blog">读文章</RouterLink>
            <a class="btn btn-ghost" href="https://github.com/tsgfx" target="_blank" rel="noopener">GitHub</a>
            <RouterLink v-if="signedIn" class="btn btn-ghost" to="/ask">问我的笔记</RouterLink>
          </div>

          <ul class="links">
            <li v-for="link in profile.links" :key="link.label">
              <a
                v-if="linkKind(link.href) === 'external'"
                :href="link.href"
                :title="link.hint"
                target="_blank"
                rel="noopener"
              >
                {{ link.label }}
              </a>
              <a v-else-if="linkKind(link.href) === 'raw'" :href="link.href" :title="link.hint">{{ link.label }}</a>
              <RouterLink v-else-if="linkKind(link.href) === 'internal'" :to="link.href!" :title="link.hint">
                {{ link.label }}
              </RouterLink>
              <span v-else class="plain">{{ link.label }}</span>
            </li>
          </ul>
        </div>

        <aside class="now" aria-label="当前状态">
          <div class="now-head">
            <span class="now-label">现在</span>
            <span class="now-live" aria-hidden="true" />
          </div>
          <dl class="now-list">
            <div class="now-row">
              <dt>在读</dt>
              <dd>计算机技术 · 硕士</dd>
            </div>
            <div class="now-row">
              <dt>研究</dt>
              <dd>三维分割 · 三维重建 · 三维编辑</dd>
            </div>
            <div class="now-row">
              <dt>做过</dt>
              <dd>LLM 工程化 · C++ 高并发后端</dd>
            </div>
            <div class="now-row">
              <dt>关注</dt>
              <dd>二维分割 · 多模态感知</dd>
            </div>
          </dl>
          <p class="now-foot">太原理工大学 · 2025.09 — 2028.06</p>
        </aside>
      </div>

      <div class="scroll-hint" aria-hidden="true"><span class="scroll-line" /></div>
    </section>

    <!-- ───────── 数字 ───────── -->
    <section ref="metricsEl" class="metrics">
      <div v-for="(item, index) in profile.highlights" :key="item.label" class="metric" :style="{ '--i': index }">
        <p class="metric-num">
          {{ metricShown[index].toFixed(item.decimals) }}<span class="metric-suffix">{{ item.suffix }}</span>
        </p>
        <p class="metric-label">{{ item.label }}</p>
        <p class="metric-src">{{ item.source }}</p>
      </div>
    </section>

    <!-- ───────── 在做的事 ───────── -->
    <section class="focus reveal">
      <header class="sec-head">
        <p class="sec-kicker">Focus</p>
        <h2 class="sec-title">在做的事</h2>
      </header>
      <div class="cards">
        <article
          v-for="(item, index) in profile.focus"
          :key="item.index"
          class="card"
          :style="{ '--i': index }"
          @pointermove="tilt"
          @pointerleave="untilt"
        >
          <span class="card-index">{{ item.index }}</span>
          <h3 class="card-title">{{ item.title }}</h3>
          <p class="card-body">{{ item.body }}</p>
          <ul class="card-tags">
            <li v-for="tag in item.tags" :key="tag">{{ tag }}</li>
          </ul>
        </article>
      </div>
    </section>

    <!-- ───────── 工具箱 ───────── -->
    <div class="tools" aria-label="常用工具">
      <div class="tools-track">
        <ul class="tools-run">
          <li v-for="tool in profile.tools" :key="`a-${tool}`">{{ tool }}</li>
        </ul>
        <ul class="tools-run" aria-hidden="true">
          <li v-for="tool in profile.tools" :key="`b-${tool}`">{{ tool }}</li>
        </ul>
      </div>
    </div>

    <!-- ───────── 经历 ───────── -->
    <section class="timeline">
      <header class="sec-head reveal">
        <p class="sec-kicker">Track</p>
        <h2 class="sec-title">一路走来</h2>
      </header>

      <ol class="tracks">
        <li v-for="(item, index) in profile.tracks" :key="item.period" class="track reveal" :style="{ '--i': index }">
          <span class="track-dot" aria-hidden="true" />
          <p class="track-period">{{ item.period }}</p>
          <div class="track-main">
            <h3 class="track-role">{{ item.role }}</h3>
            <p class="track-org">
              {{ item.org }}<span v-if="item.place" class="track-place">· {{ item.place }}</span>
            </p>
            <ul class="track-points">
              <li v-for="point in item.points" :key="point">{{ point }}</li>
            </ul>
          </div>
          <ul class="track-tags">
            <li v-for="tag in item.tags" :key="tag">{{ tag }}</li>
          </ul>
        </li>
      </ol>
    </section>

    <!-- ───────── 笔记库 ───────── -->
    <section ref="libraryEl" class="library reveal">
      <div class="lib-left">
        <p class="sec-kicker">Library</p>
        <h2 class="sec-title">笔记库</h2>
        <p class="lib-line">
          已公开 <b>{{ counts.published }}</b> 篇笔记<span v-if="stats.tags"
            >，分成 <b>{{ counts.tags }}</b> 个标签</span
          >。
        </p>
        <p class="lib-note">文章会先在笔记里长出来，整理过之后才放出去。</p>
        <RouterLink class="lib-more" to="/blog">去博客看看 →</RouterLink>
      </div>

      <div class="lib-right">
        <ol v-if="posts.length" class="post-list">
          <li v-for="(post, index) in posts" :key="post.id" class="post" :style="{ '--i': index }">
            <RouterLink class="post-link" :to="`/blog/${post.slug}`">
              <span class="post-date">{{ monthDay(post.publishedAt) }}</span>
              <span class="post-title">{{ post.title }}</span>
              <span v-if="post.summary" class="post-summary">{{ post.summary }}</span>
            </RouterLink>
          </li>
        </ol>
        <p v-else-if="!libraryReady" class="quiet">正在读取…</p>
        <div v-else class="lib-empty">
          <p class="lib-empty-title">还没有公开的文章</p>
          <p class="lib-empty-sub">
            第一批正在挑。这个站是我自己写的：检索用 bge-m3 向量加关键词融合，写和发布都在同一个系统里。
          </p>
          <RouterLink v-if="signedIn" class="btn btn-ghost btn-sm" to="/notes">去笔记里挑几篇</RouterLink>
        </div>
      </div>
    </section>

    <footer class="foot">
      <p class="foot-note">{{ profile.footer }}</p>
      <div class="foot-end">
        <p class="foot-dim">内容随时会改，别太当真。</p>
        <button class="to-top" type="button" @click="scrollToTop">回到顶部 ↑</button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.home {
  width: 100%;
  max-width: var(--page);
  margin: 0 auto;
  padding: 0 var(--space-5) var(--space-7);
}

/* ───────── 首屏 ───────── */
.hero {
  position: relative;
  display: flex;
  align-items: center;
  min-height: calc(100vh - 56px);
  padding: var(--space-7) 0 var(--space-6);
  isolation: isolate;
}

/* 极细网格：纸面下的工程感。用径向遮罩淡出，避免变成「画了格子」。
   遮罩尺寸写死成 px 而不是百分比：元素盒比它大得多，用百分比的话
   网格会在盒子边缘被硬裁一刀，看得到一条直线。 */
.grid-veil {
  position: absolute;
  inset: -40% -30%;
  z-index: -3;
  pointer-events: none;
  background-image:
    linear-gradient(var(--grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid-line) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: radial-gradient(720px 600px at 20% 14%, #000 0%, rgba(0, 0, 0, 0.5) 55%, transparent 92%);
  -webkit-mask-image: radial-gradient(720px 600px at 20% 14%, #000 0%, rgba(0, 0, 0, 0.5) 55%, transparent 92%);
}

/* 纸纹：一层极淡的噪点。大片留白上纯色会显得像「空白页」，有颗粒才像纸 */
.grain {
  position: absolute;
  inset: 0;
  z-index: -3;
  pointer-events: none;
  opacity: 0.04;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 180px 180px;
}

.aurora {
  /* 内边距留得比墨晕更大：overflow 裁切会把模糊边缘切成直线，很显眼 */
  position: absolute;
  inset: -26% -14%;
  z-index: -2;
  overflow: hidden;
  pointer-events: none;
}

.aurora i {
  position: absolute;
  display: block;
  border-radius: 50%;
  filter: blur(70px);
  will-change: transform;
}

/* 两团墨晕反向缓慢漂移，19s 一个来回，眼睛几乎察觉不到周期 */
.blob-a {
  top: 6%;
  left: 10%;
  width: 38vw;
  height: 38vw;
  background: var(--aurora-1);
  animation: drift-a 19s var(--ease-soft) infinite alternate;
}

.blob-b {
  right: 8%;
  bottom: 2%;
  width: 32vw;
  height: 32vw;
  background: var(--aurora-2);
  animation: drift-b 23s var(--ease-soft) infinite alternate;
}

@keyframes drift-a {
  to {
    transform: translate3d(6%, 8%, 0) scale(1.12);
  }
}

@keyframes drift-b {
  to {
    transform: translate3d(-8%, -6%, 0) scale(1.08);
  }
}

/* 指针光斑：跟着鼠标走的柔光，只有精确指针设备才显示 */
.spot {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: radial-gradient(360px circle at var(--mx, 50%) var(--my, 40%), var(--spot), transparent 72%);
  opacity: 0;
  transition: opacity var(--dur-3) var(--ease-soft);
}

@media (hover: hover) and (pointer: fine) {
  .hero:hover .spot {
    opacity: 1;
  }
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(280px, 0.85fr);
  gap: var(--space-7);
  align-items: center;
  width: 100%;
}

.hero-main {
  min-width: 0;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-2);
  animation: rise 700ms var(--ease-out) backwards;
}

.eyebrow-sep {
  color: var(--hairline);
}

.eyebrow-place {
  color: var(--ink-3);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pen);
  animation: halo 2.4s var(--ease-soft) infinite;
}

@keyframes halo {
  0% {
    box-shadow: 0 0 0 0 rgba(47, 93, 87, 0.35);
  }
  70%,
  100% {
    box-shadow: 0 0 0 8px rgba(47, 93, 87, 0);
  }
}

/* 名字：每个字在独立遮罩里向上推出，比淡入更「落地」 */
.name {
  margin: var(--space-4) 0 0;
  font-family: var(--font-serif);
  font-size: clamp(52px, 7.4vw, 96px);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: 0.02em;
}

/* 包一层 inline-block，宽度就等于名字本身的宽度，下划线才能刚好压在名字下面 */
.name-wrap {
  position: relative;
  display: inline-block;
  padding-bottom: 13px;
}

.name-line {
  display: flex;
  flex-wrap: wrap;
}

.char {
  display: inline-block;
  overflow: hidden;
  padding-bottom: 0.04em;
  transition: transform 340ms var(--ease-out);
  transition-delay: calc(var(--c) * 45ms);
}

/* 鼠标压上去时，三个字依次抬起一点——像纸张被指尖掀起一角 */
.name:hover .char {
  transform: translateY(-5px);
}

.char i {
  display: inline-block;
  font-style: normal;
  animation: char-up 820ms var(--ease-out) backwards;
  animation-delay: calc(var(--c) * 90ms + 80ms);
}

@keyframes char-up {
  from {
    opacity: 0;
    transform: translateY(104%);
  }
}

/* 手画的下划线：pathLength=1 让 dasharray 与路径实际长度无关。
   必须绝对定位——svg 没有宽高属性时自带 300px 的固有尺寸，会把包裹盒撑宽，
   名字短的时候下划线就戳到字外面去了。 */
.stroke {
  position: absolute;
  bottom: 0;
  left: 0;
  display: block;
  width: 100%;
  height: 13px;
  overflow: visible;
}

.stroke path {
  fill: none;
  stroke: var(--pen);
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: draw 1000ms var(--ease-out) 560ms forwards;
}

@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}

/* 收笔处的一滴墨：下划线画完后散开一次，然后消失 */
.ink-drop {
  position: absolute;
  right: 1px;
  bottom: 3px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--pen);
  opacity: 0;
  animation: ink-drop 1500ms var(--ease-out) 1420ms 1 forwards;
}

@keyframes ink-drop {
  0% {
    opacity: 0;
    transform: scale(0.4);
    filter: blur(0);
  }
  16% {
    opacity: 0.9;
    transform: scale(1.15);
  }
  100% {
    opacity: 0;
    transform: scale(7);
    filter: blur(3px);
  }
}

.latin {
  margin-top: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-3);
  animation: rise 700ms var(--ease-out) 380ms backwards;
}

.latin-dot {
  margin: 0 var(--space-2);
  color: var(--hairline);
}

/* 定位：字距从散到拢，入场时有一种「对上焦」的感觉 */
.role {
  margin-top: var(--space-4);
  font-size: var(--text-md);
  letter-spacing: 0.16em;
  color: var(--pen);
  animation: tracking-in 1100ms var(--ease-out) 420ms backwards;
}

@keyframes tracking-in {
  from {
    opacity: 0;
    letter-spacing: 0.5em;
    filter: blur(4px);
  }
}

.intro {
  max-width: 34rem;
  margin-top: var(--space-4);
  color: var(--ink-2);
  font-size: var(--text-md);
  line-height: 1.95;
  animation: rise 700ms var(--ease-out) 620ms backwards;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-6);
  animation: rise 700ms var(--ease-out) 720ms backwards;
}

.links {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-5);
  margin: var(--space-5) 0 0;
  padding: 0;
  list-style: none;
  font-size: var(--text-sm);
  animation: rise 700ms var(--ease-out) 820ms backwards;
}

.links a {
  color: var(--ink-2);
  border-bottom: 1px solid var(--hairline);
  padding-bottom: 1px;
  transition:
    color var(--dur-1) var(--ease-soft),
    border-color var(--dur-1) var(--ease-soft);
}

.links a:hover {
  color: var(--pen);
  border-color: var(--pen);
  text-decoration: none;
}

.plain {
  color: var(--ink-3);
}

/* ───────── 「现在」卡片 ───────── */
.now {
  position: relative;
  padding: var(--space-5);
  border: 1px solid var(--hairline);
  border-radius: 3px;
  background: var(--surface);
  box-shadow: var(--lift-2);
  animation: rise 820ms var(--ease-out) 700ms backwards;
}

/* 左侧一道向下的钢笔绿：卡片像被笔划开 */
.now::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 2px;
  background: linear-gradient(var(--pen), transparent 92%);
}

.now-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.now-label {
  font-family: var(--font-serif);
  font-size: var(--text-lg);
  color: var(--ink);
}

.now-live {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--pen);
  animation: halo 2.4s var(--ease-soft) infinite;
}

.now-list {
  margin: var(--space-4) 0 0;
}

.now-row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-top: 1px solid var(--hairline);
}

.now-row dt {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--ink-3);
  padding-top: 2px;
}

.now-row dd {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.65;
  color: var(--ink);
}

.now-foot {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--hairline);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-3);
}

/* 底部下拉提示：一条 1px 线在向下滑，比箭头更安静 */
.scroll-hint {
  position: absolute;
  bottom: var(--space-5);
  left: 0;
  height: 40px;
  overflow: hidden;
  pointer-events: none;
}

.scroll-line {
  display: block;
  width: 1px;
  height: 40px;
  background: linear-gradient(var(--hairline), var(--pen));
  animation: slide-down 2.2s var(--ease-soft) infinite;
}

@keyframes slide-down {
  from {
    transform: translateY(-100%);
  }
  to {
    transform: translateY(100%);
  }
}

/* ───────── 数字条 ───────── */
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
}

.metric {
  position: relative;
  padding: var(--space-6) var(--space-5);
  transition: background-color var(--dur-2) var(--ease-soft);
}

/* 悬浮时从左侧长出一条钢笔线，压在区块上边框上 */
.metric::after {
  content: '';
  position: absolute;
  top: -1px;
  right: 0;
  left: 0;
  height: 1px;
  background: var(--pen);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--dur-3) var(--ease-out);
}

.metric:hover::after {
  transform: scaleX(1);
}

.metric + .metric {
  border-left: 1px solid var(--hairline);
}

.metric:hover {
  background: var(--row-hover);
}

.metric-num {
  font-family: var(--font-serif);
  font-size: clamp(36px, 4.4vw, 56px);
  line-height: 1;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  transition: color var(--dur-2) var(--ease-soft);
}

.metric:hover .metric-num {
  color: var(--pen);
}

.metric-suffix {
  margin-left: 2px;
  font-size: 0.5em;
  vertical-align: 0.65em;
  color: var(--pen);
}

.metric-label {
  margin-top: var(--space-3);
  font-size: var(--text-sm);
  color: var(--ink);
}

.metric-src {
  margin-top: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-3);
}

/* ───────── 区块标题 ───────── */
.sec-head {
  margin-bottom: var(--space-5);
}

.sec-kicker {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.24em;
  text-transform: uppercase;
  color: var(--ink-3);
}

/* 每节前面一小段钢笔线，把 section 的起点标出来 */
.sec-kicker::before {
  content: '';
  width: 26px;
  height: 1px;
  background: var(--pen);
  opacity: 0.65;
}

.sec-title {
  margin-top: 6px;
  font-family: var(--font-serif);
  font-size: var(--text-h2);
  font-weight: 600;
}

/* ───────── 在做的事 ───────── */
.focus {
  padding-top: var(--space-7);
}

.cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
}

.card {
  position: relative;
  padding: var(--space-5);
  border: 1px solid var(--hairline);
  border-radius: 3px;
  background: var(--surface);
  overflow: hidden;
  transform: perspective(900px) rotateX(var(--rx, 0deg)) rotateY(var(--ry, 0deg));
  transform-style: preserve-3d;
  transition:
    transform var(--dur-3) var(--ease-out),
    border-color var(--dur-2) var(--ease-soft),
    box-shadow var(--dur-2) var(--ease-soft);
}

.card:hover {
  border-color: var(--pen);
  box-shadow: var(--lift-2);
}

/* 跟着指针走的暖光，只做「被照到」的暗示 */
.card::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  background: radial-gradient(240px circle at var(--gx, 50%) var(--gy, 50%), var(--glow-warm), transparent 64%);
  opacity: 0;
  transition: opacity var(--dur-2) var(--ease-soft);
}

.card:hover::after {
  opacity: 0.85;
}

.card-index {
  display: block;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.2em;
  color: var(--ink-3);
}

.card-title {
  margin-top: var(--space-3);
  font-family: var(--font-serif);
  font-size: 21px;
  font-weight: 600;
}

.card-body {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  line-height: 1.85;
  color: var(--ink-2);
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-4) 0 0;
  padding: 0;
  list-style: none;
}

.card-tags li {
  padding: 2px 8px;
  border: 1px solid var(--hairline);
  border-radius: 20px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--ink-3);
}

/* ───────── 工具箱跑马灯 ───────── */
.tools {
  margin: var(--space-7) 0 0;
  padding: var(--space-4) 0;
  border-top: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
  overflow: hidden;
  mask-image: linear-gradient(90deg, transparent, #000 7%, #000 93%, transparent);
  -webkit-mask-image: linear-gradient(90deg, transparent, #000 7%, #000 93%, transparent);
}

.tools-track {
  display: flex;
  width: max-content;
  animation: marquee 44s linear infinite;
}

.tools:hover .tools-track {
  animation-play-state: paused;
}

@keyframes marquee {
  to {
    transform: translate3d(-50%, 0, 0);
  }
}

.tools-run {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin: 0;
  padding: 0 var(--space-5) 0 0;
  list-style: none;
}

.tools-run li {
  display: inline-flex;
  align-items: center;
  gap: var(--space-5);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--ink-3);
  white-space: nowrap;
}

.tools-run li::after {
  content: '';
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--hairline);
}

/* ───────── 时间线 ───────── */
.timeline {
  padding-top: var(--space-7);
}

.tracks {
  margin: 0;
  padding: 0;
  list-style: none;
}

.track {
  position: relative;
  padding: 0 0 var(--space-6) var(--space-5);
}

/* 每个条目自己画一段竖线，露出时才从上往下长出来 */
.track::before {
  content: '';
  position: absolute;
  top: 16px;
  bottom: 0;
  left: 3px;
  width: 1px;
  background: var(--hairline);
  transform: scaleY(0);
  transform-origin: top;
  transition: transform 720ms var(--ease-out);
}

.track:last-child::before {
  display: none;
}

.track.is-in::before {
  transform: scaleY(1);
}

.track-dot {
  position: absolute;
  top: 6px;
  left: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  border: 1px solid var(--ink-3);
  background: var(--paper);
  transform: scale(0.6);
  transition:
    transform 420ms var(--ease-out) 120ms,
    background-color 420ms var(--ease-soft) 120ms,
    border-color 420ms var(--ease-soft) 120ms;
}

.track.is-in .track-dot {
  transform: scale(1);
}

.track:first-child .track-dot {
  border-color: var(--pen);
  background: var(--pen);
  box-shadow: 0 0 0 4px var(--pen-soft);
}

.track-period {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.track-main {
  margin-top: var(--space-2);
}

.track-role {
  font-family: var(--font-serif);
  font-size: 19px;
  font-weight: 600;
}

.track-org {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--pen);
}

.track-place {
  margin-left: var(--space-2);
  color: var(--ink-3);
}

.track-points {
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
  max-width: 46rem;
}

.track-points li {
  position: relative;
  padding-left: var(--space-4);
  font-size: var(--text-sm);
  line-height: 1.85;
  color: var(--ink-2);
}

.track-points li + li {
  margin-top: 4px;
}

.track-points li::before {
  content: '';
  position: absolute;
  top: 0.82em;
  left: 2px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--hairline);
}

.track-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
}

.track-tags li {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--ink-3);
  padding: 2px 8px;
  border: 1px solid var(--hairline);
  border-radius: 20px;
}

/* ───────── 笔记库 ───────── */
.library {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
  gap: var(--space-7);
  padding-top: var(--space-6);
  border-top: 1px solid var(--hairline);
}

.lib-line {
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  line-height: 1.9;
  color: var(--ink-2);
}

.lib-line b {
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.lib-note {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-3);
}

.lib-more {
  display: inline-block;
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  color: var(--pen);
}

.quiet {
  padding: var(--space-4) 0;
  color: var(--ink-3);
  font-size: var(--text-sm);
  line-height: 1.8;
}

.lib-empty {
  position: relative;
  padding: var(--space-5);
  border: 1px solid var(--hairline);
  background: var(--surface);
}

/* 与「现在」卡片同一套语言：左侧一道钢笔绿 */
.lib-empty::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 2px;
  background: linear-gradient(var(--pen), transparent 90%);
}

.lib-empty-title {
  font-family: var(--font-serif);
  font-size: var(--text-lg);
  color: var(--ink);
}

.lib-empty-sub {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  line-height: 1.85;
  color: var(--ink-3);
}

.lib-empty .btn {
  margin-top: var(--space-4);
}

.post-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--hairline);
}

.post {
  border-bottom: 1px solid var(--hairline);
}

.post-link {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 2px var(--space-4);
  align-items: baseline;
  padding: var(--space-4) var(--space-3);
  margin: 0 calc(var(--space-3) * -1);
  color: var(--ink);
  transition:
    background-color var(--dur-1) var(--ease-soft),
    box-shadow var(--dur-2) var(--ease-soft);
}

.post-link:hover {
  background: var(--row-hover);
  box-shadow: inset 2px 0 0 var(--pen);
  text-decoration: none;
}

.post-date {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  transition: color var(--dur-1);
}

.post-link:hover .post-date {
  color: var(--pen);
}

.post-title {
  font-family: var(--font-serif);
  font-size: 19px;
  line-height: 1.45;
  transition: color var(--dur-1);
}

.post-link:hover .post-title {
  color: var(--pen);
}

.post-summary {
  grid-column: 2;
  color: var(--ink-3);
  font-size: var(--text-sm);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ───────── 页脚 ───────── */
.foot {
  margin-top: var(--space-7);
  padding-top: var(--space-5);
  border-top: 1px solid var(--hairline);
  font-size: var(--text-xs);
  color: var(--ink-3);
}

.foot-end {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-top: var(--space-2);
}

.foot-dim {
  opacity: 0.72;
}

.to-top {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--text-xs);
  color: var(--ink-3);
  cursor: pointer;
  transition: color var(--dur-1) var(--ease-soft);
}

.to-top:hover {
  color: var(--pen);
}

/* ───────── 滚动进入才显示 ───────── */
.reveal {
  opacity: 0;
  transform: translateY(16px);
  transition:
    opacity 620ms var(--ease-soft),
    transform 620ms var(--ease-out);
  transition-delay: calc(var(--i, 0) * 60ms);
}

.reveal.is-in {
  opacity: 1;
  transform: none;
}

/* ───────── 响应式 ───────── */
/* 平板竖屏（761–980px）是最容易难看的区间：数字四列会挤成一团、
   卡片单列又被拉得满屏空旷——所以这一档保持首屏两栏，数字与卡片改两列 */
@media (max-width: 980px) {
  .hero-grid {
    grid-template-columns: minmax(0, 1.4fr) minmax(230px, 0.8fr);
    gap: var(--space-6);
  }

  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric {
    padding: var(--space-5) var(--space-4);
  }

  /* 两列后第一列不该再有左分隔线，第二行补上边框线 */
  .metric:nth-child(odd) {
    border-left: 0;
  }

  .metric:nth-child(n + 3) {
    border-top: 1px solid var(--hairline);
  }

  .library {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-6);
  }
}

@media (max-width: 760px) {
  .home {
    padding: 0 var(--space-4) var(--space-6);
  }

  .hero {
    min-height: auto;
    padding: var(--space-6) 0 var(--space-5);
  }

  /* 手机上首屏收成单列，「现在」卡片跟在正文后面 */
  .hero-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-5);
  }

  .now {
    max-width: 30rem;
  }

  .name {
    font-size: clamp(44px, 13vw, 64px);
  }

  .intro {
    line-height: 1.9;
  }

  .post-link {
    grid-template-columns: minmax(0, 1fr);
  }

  .post-summary {
    grid-column: 1;
  }

  .scroll-hint {
    display: none;
  }
}

/* 小屏手机（≤640px）：卡片回到单列，标题和正文才喘得过气 */
@media (max-width: 640px) {
  .cards {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .tools-track {
    animation: none;
    width: auto;
    flex-wrap: wrap;
  }

  /* 不做滚动时第二份是纯重复内容，直接去掉 */
  .tools-run:nth-child(2) {
    display: none;
  }

  .tools-run {
    flex-wrap: wrap;
    row-gap: var(--space-2);
  }

  .track::before {
    transform: scaleY(1);
  }

  .track-dot {
    transform: none;
  }
}
</style>
