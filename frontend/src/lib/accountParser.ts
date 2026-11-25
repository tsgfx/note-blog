/**
 * 账号凭据文本解析：把「账号 / 密码 / 辅助邮箱 / 2FA」混排文本拆成规整字段。
 *
 * 设计要点（都是被真实脏数据逼出来的）：
 * - 判定顺序是 **标签 > 位置 > 形状**。标签写了什么就信什么，形状只在标签没覆盖时填空。
 * - 密码不按分隔符切：用「账号结束 → 辅助邮箱开始」之间的原始切片，所以密码含 @、含空格、
 *   含 `----` 都不会被切坏，也不会被当成邮箱。
 * - 2FA 只认标签或明确位置（最后一段且是 6 位数字 / base32），绝不因为「看起来像数字」就认定，
 *   `123456` 完全可能是密码。
 * - 形状判定按整个字段来（1 位 `3` 不会因为「长度不到 8」被当别的），base32 必须能排除
 *   `PASSWORD` / `SUPERSECRETPASSWORD` 这类全大写单词。
 *
 * 本文件刻意不 import 任何东西：`node --test --experimental-strip-types` 可以直接跑，
 * 也便于把这个纯函数搬到别处。
 */

export type FieldKey = 'account' | 'password' | 'recoveryEmail' | 'code2fa' | 'totpSecret' | 'totpUri'
export type FieldSource = 'label' | 'position' | 'heuristic'
export type Confidence = 'high' | 'medium' | 'low'

export interface ParsedField {
  value: string
  source: FieldSource
  /** 非 high 的字段在界面上标「启发式推断」，解析错了能一眼看出来。 */
  confidence: Confidence
}

export interface ParsedRecord {
  account: string
  password: string
  recoveryEmail: string
  code2fa: string
  totpSecret: string
  /** otpauth:// URI 原样保留，可从中读出 digits/period/algorithm。 */
  totpUri: string
  note: string
  raw: string
  fields: Partial<Record<FieldKey, ParsedField>>
}

const BOM_CHAR = String.fromCharCode(0xfeff)
const NBSP_CHAR = String.fromCharCode(0x00a0)
const IDEOGRAPHIC_SPACE_CHAR = String.fromCharCode(0x3000)
const ZERO_WIDTH_CHARS = [0x200b, 0x200c, 0x200d, 0x2060, 0xfeff].map((code) => String.fromCharCode(code))
const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/
const PHONE = /^\+?\d{7,15}$/
const SIX_DIGITS = /^\d{6}$/
const OTPAUTH = /^otpauth:\/\/(totp|hotp)\//i
/** 整行只由分隔字符组成（≥3 个）时当记录分隔线，不是字段分隔。 */
const SEPARATOR_LINE = /^[-=*_]{3,}$/
const FIELD_SEPARATOR = /\s*(?:----|---|\||\t)\s*/g

/** 去 BOM / CRLF / 不换行空格 / 全角空格 / 零宽字符。**不 trim 每行**：密码尾部空格可能是真的。 */
export function normalize(raw: string): string {
  const withoutZeroWidth = raw.split(new RegExp(`[${ZERO_WIDTH_CHARS.join('')}]`, 'u')).join('')
  const text = withoutZeroWidth
    .replace(/\r\n?/g, '\n')
    .split(NBSP_CHAR)
    .join(' ')
    .split(IDEOGRAPHIC_SPACE_CHAR)
    .join(' ')
  return text.startsWith(BOM_CHAR) ? text.slice(1) : text
}

/** 把文本按「空行 / 分隔线」切成一条条记录。 */
function splitRecords(text: string): string[] {
  const out: string[] = []
  let buffer: string[] = []
  const flush = () => {
    const joined = buffer.join('\n')
    if (joined.trim()) out.push(joined)
    buffer = []
  }
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) {
      flush()
      continue
    }
    if (SEPARATOR_LINE.test(trimmed)) {
      flush()
      continue
    }
    buffer.push(line)
  }
  flush()
  return out
}

/**
 * 从一段文本里扫出所有括号平衡的 JSON 对象（支持一行 JSON 与多行 JSON 混排）。
 * 不用整体 JSON.parse：真实数据里常是「一行 JSON + 几行普通文本」混在一起。
 */
function scanJsonObjects(text: string): { start: number; end: number; value: Record<string, unknown> }[] {
  const found: { start: number; end: number; value: Record<string, unknown> }[] = []
  let index = 0
  while (index < text.length) {
    const open = text.indexOf('{', index)
    if (open < -1) break
    if (open < 0) break
    let depth = 0
    let inString = false
    let escaped = false
    let end = -1
    for (let i = open; i < text.length; i += 1) {
      const ch = text[i]
      if (inString) {
        if (escaped) escaped = false
        else if (ch === '\\') escaped = true
        else if (ch === '"') inString = false
        continue
      }
      if (ch === '"') inString = true
      else if (ch === '{') depth += 1
      else if (ch === '}') {
        depth -= 1
        if (depth === 0) {
          end = i + 1
          break
        }
      }
    }
    if (end < 0) break
    const slice = text.slice(open, end)
    let value: unknown
    try {
      value = JSON.parse(slice)
    } catch {
      value = null
    }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      found.push({ start: open, end, value: value as Record<string, unknown> })
      index = end
    } else {
      index = open + 1
    }
  }
  return found
}

const JSON_KEYS: { field: FieldKey; keys: string[] }[] = [
  { field: 'account', keys: ['account', 'username', 'user_name', 'login', 'email', 'mail', '账号', '邮箱', '用户名'] },
  { field: 'password', keys: ['password', 'passwd', 'pwd', 'pass', '密码'] },
  {
    field: 'recoveryEmail',
    keys: [
      'recovery_email',
      'recoveryemail',
      'backup_email',
      'backupemail',
      'alt_email',
      '辅助邮箱',
      '备用邮箱',
      '恢复邮箱',
    ],
  },
  { field: 'code2fa', keys: ['code', 'otp', 'otp_code', 'two_factor_code', '2fa', '2fa_code', 'totp_code', '验证码'] },
  {
    field: 'totpSecret',
    keys: [
      'totp_secret',
      'totpsecret',
      'otp_secret',
      'otpsecret',
      'two_factor_secret',
      '2fa_secret',
      'secret',
      'seed',
      '密钥',
    ],
  },
  { field: 'totpUri', keys: ['otpauth', 'otp_url', 'otpauth_url', 'qr', 'uri', 'url'] },
]

/** JSON 对象 → 字段。支持一层嵌套（`{"credentials":{...}}` 这种导出很常见）。 */
function fromJsonObject(value: Record<string, unknown>, out: Partial<Record<FieldKey, ParsedField>>, depth = 0) {
  for (const [key, raw] of Object.entries(value)) {
    if (raw && typeof raw === 'object' && !Array.isArray(raw) && depth === 0) {
      fromJsonObject(raw as Record<string, unknown>, out, depth + 1)
      continue
    }
    const lowered = key.trim().toLowerCase().replace(/[\s-]/g, '_')
    const match = JSON_KEYS.find((entry) => entry.keys.some((candidate) => candidate.toLowerCase() === lowered))
    if (!match) continue
    if (match.field === 'totpUri') {
      const text = String(raw ?? '').trim()
      if (OTPAUTH.test(text)) out.totpUri = { value: text, source: 'label', confidence: 'high' }
      continue
    }
    const text = raw === null || raw === undefined ? '' : String(raw).trim()
    if (!text) continue
    if (!out[match.field]) out[match.field] = { value: text, source: 'label', confidence: 'high' }
  }
}

const LABELS: { field: FieldKey; pattern: RegExp }[] = [
  {
    field: 'recoveryEmail',
    pattern:
      /(辅助邮箱|备用邮箱|恢复邮箱|救援邮箱|安全邮箱|备份邮箱|recovery[ _-]?(?:email|mail)|backup[ _-]?(?:email|mail)|alt[ _-]?email)/gi,
  },
  { field: 'totpSecret', pattern: /((?:totp|otp|2fa)[ _-]?(?:secret|key)|密钥|秘钥|secret|seed)/gi },
  {
    field: 'code2fa',
    pattern: /(验证码|动态码|两步验证码|二步验证码|二次验证码|2fa[ _-]?code|otp[ _-]?code|verification[ _-]?code)/gi,
  },
  { field: 'password', pattern: /(密码|密碼|password|passwd|pwd)/gi },
  {
    field: 'account',
    pattern: /(账号|帐号|账户|用户名|登录名|邮箱|account|username|user[ _-]?name|login|email|mail)/gi,
  },
]

interface LabelHit {
  field: FieldKey
  start: number
  end: number
}

/**
 * 标签右侧是不是真的分隔符。这一步是为了别把 `PASSWORD`、`user@example.com` 里的
 * `password` / `user` 当成标签：它们后面紧跟字母、`@` 或 `-`，都不是标签该有的样子。
 */
function labelEndsHere(record: string, end: number): boolean {
  const rest = record.slice(end)
  const delimiter = DELIMITER.exec(rest)
  if (!delimiter) return false
  const tail = rest.slice(delimiter[0].length)
  return !tail || !/[-=*_|]/.test(tail[0])
}

/** 已经被形状认出来的片段（邮箱 / otpauth / base32 …）里的文字不参与标签匹配。 */
function protectedSpans(record: string): { start: number; end: number }[] {
  const spans: { start: number; end: number }[] = []
  let last = 0
  FIELD_SEPARATOR.lastIndex = 0
  let match: RegExpExecArray | null
  const consider = (start: number, end: number) => {
    const segment = record.slice(start, end).trim()
    if (segment && classify(segment) !== 'unknown') spans.push({ start, end })
  }
  while ((match = FIELD_SEPARATOR.exec(record)) !== null) {
    consider(last, match.index)
    last = match.index + match[0].length
  }
  consider(last, record.length)
  return spans
}

/** 扫描标签。优先级高的标签先占位置（「辅助邮箱」必须先于「邮箱」占位）。 */
function findLabels(record: string, blocked: { start: number; end: number }[]): LabelHit[] {
  const hits: LabelHit[] = []
  const taken = (start: number, end: number) =>
    hits.some((hit) => start < hit.end && end > hit.start) ||
    blocked.some((range) => start < range.end && end > range.start)
  for (const { field, pattern } of LABELS) {
    pattern.lastIndex = 0
    let match: RegExpExecArray | null
    while ((match = pattern.exec(record)) !== null) {
      const start = match.index
      const end = start + match[0].length
      if (!taken(start, end) && labelEndsHere(record, end)) hits.push({ field, start, end })
      if (pattern.lastIndex === match.index) pattern.lastIndex += 1
    }
  }
  return hits.sort((a, b) => a.start - b.start)
}

// 标签分隔符只认冒号 / 等号 / 空白：`----` 是字段分隔，不是「标签 = 值」的分隔
const DELIMITER = /^[\s:：=＝]*(?:是|为)?[\s:：=＝]*/

function base32ish(token: string): boolean {
  const compact = token.replace(/[\s-]/g, '').toUpperCase()
  if (compact.length < 16) return false
  if (!/^[A-Z2-7]+$/.test(compact)) return false
  if (!/[2-7]/.test(compact)) return false
  const remainder = compact.length % 8
  return remainder === 0 || remainder === 2 || remainder === 4 || remainder === 5 || remainder === 7
}

/** 记录里的一个片段属于哪种形状。顺序即优先级。 */
function classify(token: string): FieldKey | 'otpUri' | 'unknown' {
  const text = token.trim()
  if (!text) return 'unknown'
  if (OTPAUTH.test(text)) return 'otpUri'
  if (EMAIL.test(text)) return 'recoveryEmail'
  if (SIX_DIGITS.test(text)) return 'code2fa'
  if (PHONE.test(text)) return 'account'
  if (base32ish(text)) return 'totpSecret'
  return 'unknown'
}

// 按 UTF-16 下标抹空白（不能先 spread 成字符数组：emoji 会占两个码元，下标整体错位）
function mask(record: string, ranges: { start: number; end: number }[]): string {
  let result = record
  for (const range of [...ranges].sort((a, b) => b.start - a.start)) {
    const start = Math.max(0, range.start)
    const end = Math.min(result.length, range.end)
    if (end <= start) continue
    result = result.slice(0, start) + result.slice(start, end).replace(/[^\n]/g, ' ') + result.slice(end)
  }
  return result
}

function splitByFieldSeparator(text: string): { segments: string[]; separators: string[] } {
  const segments: string[] = []
  const separators: string[] = []
  let last = 0
  FIELD_SEPARATOR.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = FIELD_SEPARATOR.exec(text)) !== null) {
    segments.push(text.slice(last, match.index))
    separators.push(match[0].trim() || '|')
    last = match.index + match[0].length
  }
  segments.push(text.slice(last))
  return { segments, separators }
}

function parseTextRecord(record: string): ParsedRecord {
  const out: ParsedRecord = {
    account: '',
    password: '',
    recoveryEmail: '',
    code2fa: '',
    totpSecret: '',
    totpUri: '',
    note: '',
    raw: record.trim(),
    fields: {},
  }
  const extras: string[] = []
  const set = (field: FieldKey, value: string, source: FieldSource, confidence: Confidence) => {
    const trimmed = value.trim()
    if (!trimmed || out[field]) return false
    out[field] = trimmed
    out.fields[field] = { value: trimmed, source, confidence }
    return true
  }

  // 第一步：标签。值是「标签之后 → 下一个标签之前」的原始片段，遇到换行或字段分隔符截断。
  const hits = findLabels(record, protectedSpans(record))
  const consumed: { start: number; end: number }[] = []
  hits.forEach((hit, index) => {
    const delimiterMatch = DELIMITER.exec(record.slice(hit.end))
    const valueStart = hit.end + (delimiterMatch ? delimiterMatch[0].length : 0)
    const nextStart = index + 1 < hits.length ? hits[index + 1].start : record.length
    let valueEnd = nextStart
    const newline = record.indexOf('\n', valueStart)
    if (newline >= 0 && newline < valueEnd) valueEnd = newline
    const fieldSep = record.slice(valueStart, valueEnd).search(/----|---|\||\t/)
    if (fieldSep >= 0) valueEnd = valueStart + fieldSep
    let value = record.slice(valueStart, valueEnd).trim()
    if (!value) {
      // 标签独占一行、值在下一行：真实导出里很常见
      const rest = record.slice(nextStart)
      const nextLine = rest.split('\n').find((line) => line.trim())
      if (nextLine) {
        value = nextLine.trim()
        const lineStart = record.indexOf(nextLine, nextStart)
        consumed.push({ start: lineStart, end: lineStart + nextLine.length })
      }
    }
    consumed.push({ start: hit.start, end: valueEnd })
    if (hit.field === 'totpSecret') {
      securityAssignTotp(value)
      return
    }
    set(hit.field, value, 'label', 'high')
  })

  function securityAssignTotp(value: string) {
    if (!value) return
    if (OTPAUTH.test(value)) out.totpUri = value
    else set('totpSecret', value, 'label', 'high')
  }

  // 第二步：位置。没有标签的记录按字段分隔符切，密码用区间切片保住内部的 `----`。
  const residual = mask(record, consumed)
  const { segments, separators } = splitByFieldSeparator(residual)
  const cleaned = segments.map((segment) => segment.trim()).filter(Boolean)

  if (!out.account && !out.password && cleaned.length >= 2) {
    const first = cleaned[0]
    const firstKind = classify(first)
    // 第一段本来就是邮箱/手机号时，位置判定也算可信；否则只能是「猜的」
    const accountConfidence: Confidence = firstKind === 'unknown' ? 'medium' : 'high'
    set('account', first, 'position', accountConfidence)

    const last = cleaned[cleaned.length - 1]
    const lastKind = classify(last)
    if (lastKind === 'code2fa') set('code2fa', last, 'position', 'high')
    else if (lastKind === 'totpSecret') set('totpSecret', last, 'position', 'medium')
    else if (lastKind === 'otpUri') out.totpUri = last

    // 从右往左找辅助邮箱（必须不是第一段，也不该是最后一段 2FA）
    let recoveryIndex = -1
    for (let i = cleaned.length - 1; i >= 1; i -= 1) {
      if (i === cleaned.length - 1 && out.code2fa && cleaned[i] === out.code2fa) continue
      if (EMAIL.test(cleaned[i])) {
        recoveryIndex = i
        break
      }
    }
    if (recoveryIndex > 0) set('recoveryEmail', cleaned[recoveryIndex], 'position', 'high')

    const accountSegment = cleaned[0]
    const accountStart = record.indexOf(accountSegment)
    const accountEnd = accountStart + accountSegment.length
    const passwordStart = accountEnd + (separators[0]?.length ?? 0)
    if (recoveryIndex > 0) {
      const recoveryStart = record.indexOf(cleaned[recoveryIndex], passwordStart)
      if (recoveryStart > passwordStart) {
        const slice = record.slice(passwordStart, recoveryStart).trim()
        // 切片里若还带着分隔符尾巴，去掉尾部分隔符
        const cleanedPassword = slice.replace(/(?:\s*(?:----|---|\||\t)\s*)+$/, '').trim()
        if (cleanedPassword) set('password', cleanedPassword, 'position', 'medium')
      }
    } else if (cleaned.length >= 3) {
      const tailStart = record.indexOf(cleaned[cleaned.length - 1], passwordStart)
      if (tailStart > passwordStart) {
        const slice = record
          .slice(passwordStart, tailStart)
          .trim()
          .replace(/(?:\s*(?:----|---|\||\t)\s*)+$/, '')
        if (slice) set('password', slice, 'position', 'medium')
      }
    } else if (cleaned.length === 2) {
      set('password', cleaned[1], 'position', 'medium')
    }
  }

  // 第三步：形状。只填空，不覆盖标签/位置已经给出的值。
  for (const token of cleaned) {
    if (token === out.account || token === out.password || token === out.recoveryEmail) continue
    if (token === out.code2fa || token === out.totpSecret || token === out.totpUri) continue
    const kind = classify(token)
    if (kind === 'otpUri') {
      if (!out.totpUri) out.totpUri = token
      else extras.push(token)
      continue
    }
    if (kind === 'recoveryEmail') {
      if (!out.recoveryEmail) set('recoveryEmail', token, 'heuristic', 'low')
      else extras.push(token)
      continue
    }
    if (kind === 'code2fa') {
      if (!out.code2fa) set('code2fa', token, 'heuristic', 'low')
      else extras.push(token)
      continue
    }
    if (kind === 'account') {
      if (!out.account) set('account', token, 'heuristic', 'low')
      else extras.push(token)
      continue
    }
    if (kind === 'totpSecret') {
      if (!out.totpSecret) set('totpSecret', token, 'heuristic', 'low')
      else extras.push(token)
      continue
    }
    if (!out.password) set('password', token, 'heuristic', 'low')
    else extras.push(token)
  }

  if (extras.length) out.note = `多余字段：${extras.join(' / ')}`
  if (out.totpUri && !out.totpSecret) {
    const secret = /[?&]secret=([^&]+)/i.exec(out.totpUri)
    if (secret) set('totpSecret', decodeURIComponent(secret[1]), 'label', 'high')
  }
  return out
}

/** 解析一段任意格式的账号凭据文本。 */
export function parseAccounts(raw: string): ParsedRecord[] {
  const text = normalize(raw)
  if (!text.trim()) return []
  const records: ParsedRecord[] = []

  for (const chunk of splitRecords(text)) {
    const jsonBlocks = scanJsonObjects(chunk)
    if (!jsonBlocks.length) {
      records.push(parseTextRecord(chunk))
      continue
    }
    let cursor = 0
    for (const block of jsonBlocks) {
      const before = chunk.slice(cursor, block.start).trim()
      if (before) records.push(parseTextRecord(before))
      const out: ParsedRecord = {
        account: '',
        password: '',
        recoveryEmail: '',
        code2fa: '',
        totpSecret: '',
        totpUri: '',
        note: '',
        raw: chunk.slice(block.start, block.end).trim(),
        fields: {},
      }
      fromJsonObject(block.value, out.fields)
      for (const key of ['account', 'password', 'recoveryEmail', 'code2fa', 'totpSecret', 'totpUri'] as const) {
        if (out.fields[key]) out[key] = out.fields[key]!.value
        else if (key === 'totpUri' && out.totpUri) continue
      }
      if (out.totpUri && !out.totpSecret) {
        const secret = /[?&]secret=([^&]+)/i.exec(out.totpUri)
        if (secret) {
          out.totpSecret = decodeURIComponent(secret[1])
          out.fields.totpSecret = { value: out.totpSecret, source: 'label', confidence: 'high' }
        }
      }
      records.push(out)
      cursor = block.end
    }
    const after = chunk.slice(cursor).trim()
    if (after) records.push(parseTextRecord(after))
  }
  return records
}

/** 原始行格式：账号----密码----辅助邮箱----2FA（2FA 优先用现成验证码，否则用 TOTP 密钥）。 */
export function toRawLine(
  record: Pick<ParsedRecord, 'account' | 'password' | 'recoveryEmail' | 'code2fa' | 'totpSecret'>,
) {
  return [record.account, record.password, record.recoveryEmail, record.code2fa || record.totpSecret].join('----')
}

export function toCsv(records: ParsedRecord[]): string {
  const header = ['账号', '密码', '辅助邮箱', '2FA', 'TOTP 密钥', '备注']
  const escape = (value: string) => {
    const text = value ?? ''
    // CSV 公式注入：以 = + - @ 开头的单元前加单引号，避免被表格软件当公式执行
    const safe = /^[=+\-@]/.test(text) ? `'${text}` : text
    return /[",\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
  }
  const rows = records.map((record) =>
    [record.account, record.password, record.recoveryEmail, record.code2fa, record.totpSecret, record.note]
      .map(escape)
      .join(','),
  )
  return [header.join(','), ...rows].join('\n')
}
