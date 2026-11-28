import { clearAuthSession, hasAuthSession } from '../lib/authSession'
import { post } from './client'

export interface LoginResult {
  token: string
  username: string
  displayName: string
}

export function login(username: string, password: string) {
  return post<LoginResult>('/auth/login', { username, password })
}

export function isLoggedIn() {
  return hasAuthSession()
}

export function logout() {
  clearAuthSession()
}

/**
 * 修改密码。成功后服务端会吊销此前签发的所有令牌，
 * 所以调用方必须清掉本地 token 并重新登录。
 */
export function changePassword(currentPassword: string, newPassword: string) {
  return post<void>('/account/password', { currentPassword, newPassword })
}
