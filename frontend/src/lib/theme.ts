import { ref } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'notes_blog_theme'

export const theme = ref<Theme>(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')

export function setTheme(next: Theme) {
  theme.value = next
  document.documentElement.dataset.theme = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    /* 隐私模式下无法保存，仅本次生效 */
  }
}

export function toggleTheme() {
  setTheme(theme.value === 'dark' ? 'light' : 'dark')
}
