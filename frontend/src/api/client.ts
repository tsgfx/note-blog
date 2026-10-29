import axios from 'axios'

/** 后端统一响应体 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('notes_blog_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

/**
 * 只有真正的「未认证」才跳转登录页。
 * 注意：Spring Security 会把未授权的错误页也返回 401，
 * 因此要区分「缺少/过期令牌」与「服务端其它错误」，避免误跳转。
 */
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const body = error?.response?.data
    // 401 统一表示未认证（后端的错误页已放行，服务端故障不会再被掩盖成 401）
    if (status === 401) {
      localStorage.removeItem('notes_blog_token')
      localStorage.removeItem('notes_blog_user')
      const here = window.location.pathname + window.location.search
      if (!window.location.pathname.startsWith('/login')) {
        window.location.assign(`/login?reason=expired&redirect=${encodeURIComponent(here)}`)
      }
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    if (status && body && typeof body === 'object' && 'message' in body) {
      return Promise.reject(new Error(String(body.message)))
    }
    return Promise.reject(new Error(status ? `请求失败（${status}）` : '无法连接服务器'))
  },
)

/** GET 并解包 ApiResponse，code !== 0 时抛错 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url, { params })
  const body = res.data
  if (body.code !== 0) throw new Error(body.message || '请求失败')
  return body.data
}

export async function post<T>(url: string, data?: unknown): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, data)
  const body = res.data
  if (body.code !== 0) throw new Error(body.message || '请求失败')
  return body.data
}

export async function patch<T>(url: string, data?: unknown): Promise<T> {
  const res = await http.patch<ApiResponse<T>>(url, data)
  const body = res.data
  if (body.code !== 0) throw new Error(body.message || '请求失败')
  return body.data
}

export async function del<T = void>(url: string): Promise<T> {
  const res = await http.delete<ApiResponse<T>>(url)
  const body = res.data
  if (body.code !== 0) throw new Error(body.message || '请求失败')
  return body.data
}
