import assert from 'node:assert/strict'
import { test } from 'node:test'
import { base32Decode, generateTotp, parseOtpAuthUri, secondsRemaining } from '../src/lib/totp.ts'

/** RFC 4226 附录 D 的种子：ASCII "12345678901234567890"，base32 即下面这串。 */
const RFC_SECRET = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'

/** 把时间固定在某个 epoch 秒，方便对 RFC 向量。 */
async function totpAt(epochSeconds: number, secret: string, digits = 6, period = 30) {
  const real = Date.now
  Date.now = () => epochSeconds * 1000
  try {
    return await generateTotp(secret, { digits, period })
  } finally {
    Date.now = real
  }
}

test('base32 严格解码：非法字符返回 null', () => {
  assert.deepEqual(base32Decode('JBSWY3DPEHPK3PXP'), base32Decode('jbsw y3dp ehpk 3pxp'))
  assert.equal(base32Decode('not base 32!'), null)
  assert.equal(base32Decode(''), null)
})

test('RFC 4226 向量：t=59 → 287082', async () => {
  assert.equal(await totpAt(59, RFC_SECRET), '287082')
})

test('RFC 4226 向量：t=1111111109 → 081804', async () => {
  assert.equal(await totpAt(1111111109, RFC_SECRET), '081804')
})

test('8 位码', async () => {
  const code = await totpAt(59, RFC_SECRET, 8)
  assert.equal(code?.length, 8)
  assert.match(code ?? '', /^[0-9]{8}$/)
})

test('非默认周期：period=60 时 t=59 与 t=0 同片', async () => {
  assert.equal(await totpAt(0, RFC_SECRET, 6, 60), await totpAt(59, RFC_SECRET, 6, 60))
})

test('otpauth URI 解析出 digits / period / algorithm', () => {
  const params = parseOtpAuthUri(
    'otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=8&period=60&algorithm=SHA256',
  )
  assert.ok(params)
  assert.equal(params?.secret, 'JBSWY3DPEHPK3PXP')
  assert.equal(params?.digits, 8)
  assert.equal(params?.period, 60)
  assert.equal(params?.algorithm, 'SHA-256')
  assert.equal(params?.issuer, 'Example')
  assert.equal(params?.account, 'alice@example.com')
})

test('HOTP URI 被标为 hotp（不按时间算）', () => {
  assert.equal(parseOtpAuthUri('otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1')?.type, 'hotp')
})

test('不是 otpauth URI 返回 null', () => {
  assert.equal(parseOtpAuthUri('JBSWY3DPEHPK3PXP'), null)
})

test('倒计时落在 1..period 之间', () => {
  const left = secondsRemaining(30)
  assert.ok(left >= 1 && left <= 30)
})
