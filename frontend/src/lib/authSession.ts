/**
 * 浏览器端认证会话的唯一所有者。
 *
 * 只有这里知道 localStorage 键名；调用方只表达「保存、读取、清除、是否已登录」。
 * token 是唯一仍在使用的持久化认证状态。历史上的 displayName 键不再写入，
 * 但清除会话时仍顺手删除，避免旧版本留下半登录状态。
 */
const TOKEN_KEY = 'notes_blog_token'
const LEGACY_USER_KEY = 'notes_blog_user'

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function hasAuthSession(): boolean {
  return Boolean(getAuthToken())
}

export function storeAuthSession(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(LEGACY_USER_KEY)
}
