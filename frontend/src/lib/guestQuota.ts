/**
 * 游客问答的本地配额。
 *
 * <p>这是「两道」里的第二道：服务端按 IP 做硬上限（进程重启也不清零），
 * 浏览器按承诺的每日条数显示剩余并提前拦住。
 *
 * <p><b>本地计数不是安全措施</b>——清缓存、换浏览器就绕过。它的职责是
 * 让用户知道自己还剩几条，而不是发出去才知道被拒。真正的拦截在服务端，
 * 这里只是把服务端的判定提前展示出来。
 *
 * <p>两边可能不同步（同一 WiFi 下别人用掉了），所以服务端每次响应会带回
 * 权威的 `remaining`，收到就以它为准并把本地覆盖掉。
 */

const KEY = 'ask-quota'
/** 与后端 app.guest.ask-daily-allowance 的默认值一致 */
export const DAILY_ALLOWANCE = 5

interface QuotaState {
  /** 日期字符串 YYYY-MM-DD（本地时区），跨天自动重置 */
  day: string
  used: number
}

function today(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function read(): QuotaState {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { day: today(), used: 0 }
    const parsed = JSON.parse(raw) as QuotaState
    // 跨天：昨天的用量不带进今天
    if (parsed.day !== today()) return { day: today(), used: 0 }
    return { day: parsed.day, used: Math.max(0, Number(parsed.used) || 0) }
  } catch {
    // 隐私模式下 localStorage 可能抛错，当作用满处理会更糟——退回 0，交给服务端兜底
    return { day: today(), used: 0 }
  }
}

function write(state: QuotaState) {
  try {
    localStorage.setItem(KEY, JSON.stringify(state))
  } catch {
    // 存不了就算了：服务端那道仍在
  }
}

/** 还剩几条（不会小于 0） */
export function remaining(): number {
  return Math.max(0, DAILY_ALLOWANCE - read().used)
}

/** 本地判断还能不能问（服务端仍可能拒绝） */
export function canAsk(): boolean {
  return remaining() > 0
}

/** 记一次本地用量。服务端已回 remaining 时用 syncFromServer 覆盖更准。 */
export function consumeLocally(): void {
  const state = read()
  write({ day: state.day, used: state.used + 1 })
}

/** 用服务端返回的权威剩余数覆盖本地 */
export function syncFromServer(serverRemaining: number): void {
  if (!Number.isFinite(serverRemaining) || serverRemaining < 0) return
  write({ day: today(), used: Math.max(0, DAILY_ALLOWANCE - serverRemaining) })
}

/** 仅用于调试/测试：清空本地计数 */
export function resetQuota(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // 忽略
  }
}
