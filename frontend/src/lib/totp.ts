/**
 * TOTP（RFC 6238）实现：只依赖 WebCrypto，不引第三方库。
 *
 * 手写而不是装 `otplib` 之类的库，是为了守住「非必要不引入」——算法本身一百行就能写完，
 * 而多一个依赖就多一份供应链风险（这个页面恰好处理的是凭据）。
 * RFC 4226 的向量测试在 `frontend/test/totp.test.ts`，证明实现没写错。
 */

export type TotpAlgorithm = 'SHA-1' | 'SHA-256' | 'SHA-512'

export interface TotpOptions {
  digits?: number
  period?: number
  algorithm?: TotpAlgorithm
}

export interface OtpAuthParams {
  type: 'totp' | 'hotp'
  secret: string
  digits: number
  period: number
  algorithm: TotpAlgorithm
  issuer: string
  account: string
}

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/**
 * base32 严格解码。非法字符、长度不足都返回 null（宁可不算，也不给出一个看起来像码的错码）。
 * 末尾残余不足 8 位的部分直接丢弃，这是 RFC 4648 定义的补位方式。
 */
export function base32Decode(input: string): Uint8Array<ArrayBuffer> | null {
  const compact = (input ?? '').replace(/[\s-]/g, '').replace(/=+$/g, '').toUpperCase()
  if (!compact) return null
  if (!/^[A-Z2-7]+$/.test(compact)) return null

  const bytes: number[] = []
  let buffer = 0
  let bits = 0
  for (const char of compact) {
    const value = BASE32_ALPHABET.indexOf(char)
    if (value < 0) return null
    buffer = (buffer << 5) | value
    bits += 5
    if (bits >= 8) {
      bytes.push((buffer >>> (bits - 8)) & 0xff)
      bits -= 8
      buffer &= (1 << bits) - 1
    }
  }
  if (!bytes.length) return null
  // 显式分配 ArrayBuffer：TS 里 `new Uint8Array(number[])` 的 buffer 类型是 ArrayBufferLike，
  // 传不进 WebCrypto 的 BufferSource（它要求 ArrayBuffer 本尊）
  const outBuffer = new ArrayBuffer(bytes.length)
  const view = new Uint8Array(outBuffer)
  view.set(bytes)
  return view
}

/** 解析 `otpauth://totp/Issuer:account?secret=...&digits=6&period=30&algorithm=SHA1`。 */
export function parseOtpAuthUri(uri: string): OtpAuthParams | null {
  const text = (uri ?? '').trim()
  const match = /^otpauth:\/\/(totp|hotp)\/([^?]*)(?:\?(.*))?$/i.exec(text)
  if (!match) return null
  const type = match[1].toLowerCase() === 'hotp' ? 'hotp' : 'totp'
  const label = decodeURIComponent(match[2] ?? '').replace(/^\/+/, '')
  const query = new URLSearchParams(match[3] ?? '')

  const secret = query.get('secret') ?? ''
  if (!secret) return null
  const digits = Number(query.get('digits') ?? 6)
  const period = Number(query.get('period') ?? 30)
  const algorithmRaw = (query.get('algorithm') ?? 'SHA1').toUpperCase().replace('-', '')
  const algorithm: TotpAlgorithm =
    algorithmRaw === 'SHA256' ? 'SHA-256' : algorithmRaw === 'SHA512' ? 'SHA-512' : 'SHA-1'

  const issuerParam = query.get('issuer') ?? ''
  const labelParts = label.split(':')
  const issuer = issuerParam || (labelParts.length > 1 ? labelParts[0] : '')
  const account = labelParts.length > 1 ? labelParts.slice(1).join(':') : label

  return {
    type,
    secret,
    digits: Number.isFinite(digits) && digits >= 6 && digits <= 10 ? digits : 6,
    period: Number.isFinite(period) && period > 0 ? period : 30,
    algorithm,
    issuer,
    account,
  }
}

function counterBytes(counter: number): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(new ArrayBuffer(8))
  let value = BigInt(Math.floor(counter))
  for (let i = 7; i >= 0; i -= 1) {
    bytes[i] = Number(value & 0xffn)
    value >>= 8n
  }
  return bytes
}

/** 当前时间片内的验证码。WebCrypto 不可用（非安全上下文）时返回 null，由界面提示而不是抛错。 */
export async function generateTotp(secret: string, options: TotpOptions = {}): Promise<string | null> {
  const digits = options.digits ?? 6
  const period = options.period ?? 30
  const algorithm = options.algorithm ?? 'SHA-1'
  const keyBytes = base32Decode(secret)
  if (!keyBytes) return null
  const subtle = globalThis.crypto?.subtle
  if (!subtle) return null

  try {
    const key = await subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: algorithm }, false, ['sign'])
    const counter = Math.floor(Date.now() / 1000 / period)
    const mac = new Uint8Array(await subtle.sign('HMAC', key, counterBytes(counter)))
    const offset = mac[mac.length - 1] & 0x0f
    const binary =
      ((mac[offset] & 0x7f) << 24) |
      ((mac[offset + 1] & 0xff) << 16) |
      ((mac[offset + 2] & 0xff) << 8) |
      (mac[offset + 3] & 0xff)
    const code = binary % 10 ** digits
    return String(code).padStart(digits, '0')
  } catch {
    return null
  }
}

/** 当前时间片还剩几秒（用于倒计时）。 */
export function secondsRemaining(period = 30): number {
  const elapsed = Math.floor(Date.now() / 1000) % period
  return period - elapsed
}
