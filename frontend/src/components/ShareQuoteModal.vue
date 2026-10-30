<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { notify } from '../lib/feedback'

const props = defineProps<{
  open: boolean
  quote: string
  articleTitle: string
  articleSlug: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

type CardTheme = 'paper' | 'sage' | 'night'
const theme = ref<CardTheme>('paper')
const isGenerating = ref(false)

const currentUrl = computed(() => {
  if (typeof window === 'undefined') return ''
  return `${window.location.origin}/blog/${props.articleSlug}`
})

const dateStr = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})

function close() {
  emit('close')
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    close()
  }
}

watch(
  () => props.open,
  (val) => {
    if (val) {
      window.addEventListener('keydown', handleKeydown)
    } else {
      window.removeEventListener('keydown', handleKeydown)
    }
  },
)

/**
 * 复制金句纯文本
 */
async function copyText() {
  const text = `“${props.quote}”\n\n—— 出自《${props.articleTitle}》\n${currentUrl.value}`
  try {
    await navigator.clipboard.writeText(text)
    notify('金句文字已复制到剪贴板', 'success')
  } catch {
    notify('复制失败，请手动选取', 'error')
  }
}

/**
 * 将卡片通过 HTML5 Canvas 绘制成高分辨率 PNG Blob
 */
async function drawCardToCanvas(): Promise<HTMLCanvasElement> {
  const canvas = document.createElement('canvas')
  const scale = 2 // Retina 2x 超采样防模糊
  const width = 640
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('无法初始化 Canvas 绘图引擎')

  // 配色方案配置
  const palette = {
    paper: {
      bg: '#fafaf7',
      primary: '#1c2326',
      secondary: '#5d666b',
      accent: '#2f5d57',
      border: '#e4e6e1',
      tagBg: '#eef2ee',
      seal: '#a4433a',
    },
    sage: {
      bg: '#244a45',
      primary: '#f5f8f6',
      secondary: '#b8c9c4',
      accent: '#e8cb8d',
      border: '#2e5d56',
      tagBg: '#1b3b37',
      seal: '#e8cb8d',
    },
    night: {
      bg: '#181e20',
      primary: '#fafaf7',
      secondary: '#8a9296',
      accent: '#7eb6ab',
      border: '#273136',
      tagBg: '#21292d',
      seal: '#d47b74',
    },
  }[theme.value]

  // 计算文本自动换行与高度
  ctx.font = '24px "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif'
  const maxTextWidth = width - 96
  const words = props.quote.split('')
  const lines: string[] = []
  let currentLine = ''

  for (let i = 0; i < words.length; i++) {
    const char = words[i]
    if (char === '\n') {
      lines.push(currentLine)
      currentLine = ''
      continue
    }
    const testLine = currentLine + char
    const metrics = ctx.measureText(testLine)
    if (metrics.width > maxTextWidth && i > 0) {
      lines.push(currentLine)
      currentLine = char
    } else {
      currentLine = testLine
    }
  }
  if (currentLine) {
    lines.push(currentLine)
  }

  const lineHeight = 42
  const textBlockHeight = lines.length * lineHeight
  const totalHeight = 120 + textBlockHeight + 160 // 顶部边距 + 正文 + 底部出处

  canvas.width = width * scale
  canvas.height = totalHeight * scale
  ctx.scale(scale, scale)

  // 1. 绘制背景与外边框
  ctx.fillStyle = palette.bg
  ctx.fillRect(0, 0, width, totalHeight)

  // 双层细线边框（东方古籍雅致感）
  ctx.strokeStyle = palette.border
  ctx.lineWidth = 1
  ctx.strokeRect(18, 18, width - 36, totalHeight - 36)
  ctx.strokeRect(22, 22, width - 44, totalHeight - 44)

  // 2. 绘制顶部徽标与日期
  ctx.fillStyle = palette.accent
  ctx.font = 'bold 15px "PingFang SC", system-ui, sans-serif'
  ctx.fillText('一隅 · 数字花园', 48, 62)

  ctx.fillStyle = palette.secondary
  ctx.font = '13px "PingFang SC", system-ui, sans-serif'
  ctx.textAlign = 'right'
  ctx.fillText(dateStr.value, width - 48, 62)

  // 3. 装饰大引号
  ctx.textAlign = 'left'
  ctx.fillStyle = palette.accent
  ctx.font = 'italic bold 56px "Georgia", serif'
  ctx.fillText('“', 44, 114)

  // 4. 正文金句
  ctx.fillStyle = palette.primary
  ctx.font = '22px/1.8 "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif'
  let y = 138
  for (const line of lines) {
    ctx.fillText(line, 48, y)
    y += lineHeight
  }

  // 5. 分割线
  ctx.strokeStyle = palette.border
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(48, y + 20)
  ctx.lineTo(width - 48, y + 20)
  ctx.stroke()

  // 6. 出处文章标题与印章
  const bottomY = y + 54
  ctx.fillStyle = palette.primary
  ctx.font = 'bold 16px "PingFang SC", system-ui, sans-serif'
  const titleText = `《${props.articleTitle}》`
  ctx.fillText(titleText, 48, bottomY)

  ctx.fillStyle = palette.secondary
  ctx.font = '12px "PingFang SC", system-ui, sans-serif'
  ctx.fillText(currentUrl.value.replace(/^https?:\/\//, ''), 48, bottomY + 26)

  // 右侧印章方块
  ctx.fillStyle = palette.seal
  ctx.strokeStyle = palette.seal
  ctx.lineWidth = 1.5
  ctx.strokeRect(width - 86, bottomY - 18, 38, 38)
  ctx.fillStyle = palette.seal
  ctx.font = 'bold 13px "Songti SC", serif'
  ctx.textAlign = 'center'
  ctx.fillText('一隅', width - 67, bottomY + 6)

  return canvas
}

/**
 * 复制卡片图片到剪贴板
 */
async function copyImage() {
  if (isGenerating.value) return
  isGenerating.value = true
  try {
    const canvas = await drawCardToCanvas()
    canvas.toBlob(async (blob) => {
      if (!blob) throw new Error('生成图片失败')
      try {
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
        notify('精美卡片已复制到剪贴板，可直接粘贴分享！', 'success')
      } catch {
        // 剪贴板权限不支持写入 blob 时，优雅降级为下载
        downloadCanvas(canvas)
        notify('浏览器限制剪贴板存图，已为您自动下载 PNG 卡片', 'info')
      } finally {
        isGenerating.value = false
      }
    }, 'image/png')
  } catch (err) {
    isGenerating.value = false
    notify('生成卡片失败：' + (err instanceof Error ? err.message : String(err)), 'error')
  }
}

/**
 * 保存下载卡片为 PNG
 */
async function saveImage() {
  if (isGenerating.value) return
  isGenerating.value = true
  try {
    const canvas = await drawCardToCanvas()
    downloadCanvas(canvas)
    notify('金句卡片已保存', 'success')
  } catch (err) {
    notify('保存卡片失败：' + (err instanceof Error ? err.message : String(err)), 'error')
  } finally {
    isGenerating.value = false
  }
}

function downloadCanvas(canvas: HTMLCanvasElement) {
  const link = document.createElement('a')
  link.download = `一隅金句-${props.articleSlug || 'quote'}.png`
  link.href = canvas.toDataURL('image/png')
  link.click()
}
</script>

<template>
  <div v-if="open" class="share-modal-backdrop" @click.self="close">
    <div class="share-modal-dialog" role="dialog" aria-modal="true" aria-labelledby="share-modal-title">
      <header class="share-modal-head">
        <h3 id="share-modal-title" class="share-modal-title">
          <span class="sparkle" aria-hidden="true">✦</span> 生成金句分享卡片
        </h3>
        <button class="share-modal-close" type="button" aria-label="关闭" @click="close">×</button>
      </header>

      <div class="share-modal-body">
        <!-- 风格切换器 -->
        <div class="theme-picker" role="radiogroup" aria-label="卡片风格">
          <button type="button" class="theme-btn" :class="{ active: theme === 'paper' }" @click="theme = 'paper'">
            <span class="theme-dot dot-paper" aria-hidden="true" /> 经典纸墨
          </button>
          <button type="button" class="theme-btn" :class="{ active: theme === 'sage' }" @click="theme = 'sage'">
            <span class="theme-dot dot-sage" aria-hidden="true" /> 远山竹青
          </button>
          <button type="button" class="theme-btn" :class="{ active: theme === 'night' }" @click="theme = 'night'">
            <span class="theme-dot dot-night" aria-hidden="true" /> 深邃夜墨
          </button>
        </div>

        <!-- 卡片实时预览 -->
        <div ref="cardEl" class="quote-card-preview" :data-card-theme="theme">
          <div class="card-inner-frame">
            <div class="card-top">
              <span class="card-brand">一隅 · 数字花园</span>
              <span class="card-date">{{ dateStr }}</span>
            </div>

            <div class="card-quote-mark" aria-hidden="true">“</div>

            <div class="card-quote-text">
              {{ quote }}
            </div>

            <div class="card-divider" aria-hidden="true" />

            <div class="card-footer">
              <div class="card-source-info">
                <span class="card-article-title">《{{ articleTitle }}》</span>
                <span class="card-url">{{ currentUrl.replace(/^https?:\/\//, '') }}</span>
              </div>
              <div class="card-seal" title="一隅印章">一隅</div>
            </div>
          </div>
        </div>
      </div>

      <footer class="share-modal-actions">
        <button class="btn btn-ghost" type="button" @click="copyText">复制文字</button>
        <button class="btn btn-ghost" type="button" :disabled="isGenerating" @click="saveImage">保存图片</button>
        <button class="btn btn-primary" type="button" :disabled="isGenerating" @click="copyImage">
          {{ isGenerating ? '生成中…' : '复制图片卡片' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.share-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  background: var(--scrim);
  backdrop-filter: blur(8px);
}

.share-modal-dialog {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 580px;
  max-height: 90vh;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
  box-shadow: var(--lift-3);
  overflow: hidden;
  animation: modal-pop 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes modal-pop {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(6px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.share-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--hairline);
}

.share-modal-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 6px;
}

.sparkle {
  color: var(--pen);
}

.share-modal-close {
  background: transparent;
  border: none;
  font-size: 22px;
  line-height: 1;
  color: var(--ink-3);
  cursor: pointer;
  padding: 4px;
}
.share-modal-close:hover {
  color: var(--ink);
}

.share-modal-body {
  padding: var(--space-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.theme-picker {
  display: flex;
  gap: var(--space-2);
  justify-content: center;
}

.theme-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  background: var(--paper-deep);
  border: 1px solid var(--hairline);
  border-radius: 20px;
  color: var(--ink-2);
  cursor: pointer;
  transition: all 0.15s ease;
}

.theme-btn.active {
  background: var(--surface);
  color: var(--ink);
  border-color: var(--pen);
  font-weight: 500;
  box-shadow: var(--lift-1);
}

.theme-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.dot-paper {
  background: #fafaf7;
  border: 1px solid #c8ccc4;
}
.dot-sage {
  background: #244a45;
}
.dot-night {
  background: #181e20;
}

/* 预览卡片外框 */
.quote-card-preview {
  padding: 18px;
  border-radius: 8px;
  transition: all 0.2s ease;
  user-select: none;
}

.card-inner-frame {
  border-radius: 4px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 纸墨风格 */
[data-card-theme='paper'] {
  background: #fafaf7;
  color: #1c2326;
  border: 1px solid #e4e6e1;
}
[data-card-theme='paper'] .card-inner-frame {
  border: 1px solid #e4e6e1;
  outline: 1px solid #e4e6e1;
  outline-offset: -5px;
}
[data-card-theme='paper'] .card-brand {
  color: #2f5d57;
  font-weight: 600;
}
[data-card-theme='paper'] .card-date {
  color: #8a9296;
}
[data-card-theme='paper'] .card-quote-mark {
  color: #2f5d57;
}
[data-card-theme='paper'] .card-quote-text {
  color: #1c2326;
}
[data-card-theme='paper'] .card-divider {
  background: #e4e6e1;
}
[data-card-theme='paper'] .card-seal {
  border-color: #a4433a;
  color: #a4433a;
}

/* 远山竹青风格 */
[data-card-theme='sage'] {
  background: #244a45;
  color: #f5f8f6;
  border: 1px solid #2e5d56;
}
[data-card-theme='sage'] .card-inner-frame {
  border: 1px solid #2e5d56;
  outline: 1px solid #2e5d56;
  outline-offset: -5px;
}
[data-card-theme='sage'] .card-brand {
  color: #e8cb8d;
  font-weight: 600;
}
[data-card-theme='sage'] .card-date {
  color: #b8c9c4;
}
[data-card-theme='sage'] .card-quote-mark {
  color: #e8cb8d;
}
[data-card-theme='sage'] .card-quote-text {
  color: #f5f8f6;
}
[data-card-theme='sage'] .card-divider {
  background: #2e5d56;
}
[data-card-theme='sage'] .card-seal {
  border-color: #e8cb8d;
  color: #e8cb8d;
}

/* 深邃夜墨风格 */
[data-card-theme='night'] {
  background: #181e20;
  color: #fafaf7;
  border: 1px solid #273136;
}
[data-card-theme='night'] .card-inner-frame {
  border: 1px solid #273136;
  outline: 1px solid #273136;
  outline-offset: -5px;
}
[data-card-theme='night'] .card-brand {
  color: #7eb6ab;
  font-weight: 600;
}
[data-card-theme='night'] .card-date {
  color: #8a9296;
}
[data-card-theme='night'] .card-quote-mark {
  color: #7eb6ab;
}
[data-card-theme='night'] .card-quote-text {
  color: #fafaf7;
}
[data-card-theme='night'] .card-divider {
  background: #273136;
}
[data-card-theme='night'] .card-seal {
  border-color: #d47b74;
  color: #d47b74;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}

.card-quote-mark {
  font-family: Georgia, serif;
  font-size: 38px;
  line-height: 1;
  font-style: italic;
  font-weight: bold;
  margin-bottom: -10px;
}

.card-quote-text {
  font-family: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', SimSun, serif;
  font-size: 17px;
  line-height: 1.85;
  letter-spacing: 0.02em;
  white-space: pre-wrap;
  word-break: break-word;
}

.card-divider {
  height: 1px;
  width: 100%;
  margin-top: 6px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: var(--space-3);
}

.card-source-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.card-article-title {
  font-size: 14px;
  font-weight: 600;
}

.card-url {
  font-size: 11px;
  opacity: 0.7;
}

.card-seal {
  width: 32px;
  height: 32px;
  border: 1.5px solid currentColor;
  border-radius: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Songti SC', serif;
  font-size: 11px;
  font-weight: bold;
  line-height: 1;
  flex-shrink: 0;
}

.share-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--hairline);
  background: var(--paper);
}
</style>
