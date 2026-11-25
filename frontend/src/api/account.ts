/**
 * 当前登录者的身份摘要。
 *
 * 这里刻意**不用** `api/client.ts` 的 axios 实例：那个实例的 401 拦截器会
 * `window.location.assign('/login?reason=expired&redirect=…')`，把守卫刚拼好的深链参数冲掉。
 * 守卫要的是「拿不到身份就安静地交给下一步处理」，所以用原生 fetch，自己判断状态码。
 */
export interface Me {
  username: string
  /** 库里可为空，后端开了 non_null 序列化，空值字段会整个消失。 */
  displayName?: string
  role: string
}

export async function getMe(): Promise<Me | null> {
  const token = localStorage.getItem('notes_blog_token')
  if (!token) return null
  const response = await fetch('/api/account/me', {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    credentials: 'omit',
  })
  if (response.status === 401) return null
  if (!response.ok) throw new Error(`身份查询失败（${response.status}）`)
  const body = (await response.json()) as { code: number; data?: Me }
  if (body.code !== 0 || !body.data) throw new Error('身份查询返回异常')
  return body.data
}

/** 角色判定口径与后端一致：令牌里的角色在 `JwtAuthFilter` 里已经大写。 */
export function isAdmin(me: Me | null): boolean {
  return (me?.role ?? '').trim().toUpperCase() === 'ADMIN'
}
