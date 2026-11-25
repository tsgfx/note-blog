import assert from 'node:assert/strict'
import { test } from 'node:test'
import { normalize, parseAccounts, toCsv, toRawLine } from '../src/lib/accountParser.ts'

function only(text: string) {
  const records = parseAccounts(text)
  assert.equal(records.length, 1, `应解析出 1 条记录，实际 ${records.length}`)
  return records[0]
}

test('分隔符格式：账号----密码----辅助邮箱----2FA', () => {
  const record = only('alice@example.com----P@ss w0rd----alice.backup@example.com----123456')
  assert.equal(record.account, 'alice@example.com')
  assert.equal(record.password, 'P@ss w0rd')
  assert.equal(record.recoveryEmail, 'alice.backup@example.com')
  assert.equal(record.code2fa, '123456')
  assert.equal(toRawLine(record), 'alice@example.com----P@ss w0rd----alice.backup@example.com----123456')
})

test('手机号账号：密码不会被当成手机号', () => {
  const record = only('13800138000----carolpass----carol@example.com')
  assert.equal(record.account, '13800138000')
  assert.equal(record.password, 'carolpass')
  assert.equal(record.recoveryEmail, 'carol@example.com')
})

test('密码含 @ 不被当成邮箱', () => {
  const record = only('bob@example.com----bob@12345----backup@example.com----123456')
  assert.equal(record.account, 'bob@example.com')
  assert.equal(record.password, 'bob@12345')
  assert.equal(record.recoveryEmail, 'backup@example.com')
})

test('密码含 ---- 时保留原文', () => {
  const record = only('a@b.com----pa----ss----c@d.com----123456')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.password, 'pa----ss')
  assert.equal(record.recoveryEmail, 'c@d.com')
  assert.equal(record.code2fa, '123456')
})

test('密码含空格时原样保留', () => {
  const record = only('a@b.com----two words here----c@d.com----123456')
  assert.equal(record.password, 'two words here')
})

test('竖线分隔 + 末尾 TOTP 密钥', () => {
  const record = only('a@b.com|pw|backup@b.com|JBSWY3DPEHPK3PXP')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.password, 'pw')
  assert.equal(record.recoveryEmail, 'backup@b.com')
  assert.equal(record.totpSecret, 'JBSWY3DPEHPK3PXP')
})

test('一行三个邮箱：多余进备注，不丢', () => {
  const record = only('a@b.com----pw----c@d.com----e@f.com')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.recoveryEmail, 'e@f.com')
  assert.match(record.note, /多余字段/)
})

test('中文标签 + 「密码是」 + 全角冒号', () => {
  const record = only('账号：a@b.com 密码是 Xyz123 辅助邮箱：c@d.com')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.password, 'Xyz123')
  assert.equal(record.recoveryEmail, 'c@d.com')
})

test('标签独占一行、值在下一行', () => {
  const record = only('账号\na@b.com\n密码\nZz9988\n辅助邮箱\nc@d.com')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.password, 'Zz9988')
  assert.equal(record.recoveryEmail, 'c@d.com')
})

test('BOM + CRLF 归一化', () => {
  const text = normalize('﻿a@b.com----pw----c@d.com----123456\r\n')
  assert.equal(text, 'a@b.com----pw----c@d.com----123456\n')
})

test('JSON 与普通文本混排', () => {
  const records = parseAccounts(
    '{"email":"bob@example.com","password":"bobpass","recovery_email":"bob.backup@example.com","totp_secret":"JBSWY3DPEHPK3PXP"}\n' +
      'carol@example.com----carolpass----carol.backup@example.com----654321',
  )
  assert.equal(records.length, 2)
  assert.equal(records[0].account, 'bob@example.com')
  assert.equal(records[0].totpSecret, 'JBSWY3DPEHPK3PXP')
  assert.equal(records[1].code2fa, '654321')
})

test('otpauth URI 里能取出密钥', () => {
  const record = only('a@b.com----pw----c@d.com----otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example')
  assert.equal(record.account, 'a@b.com')
  assert.equal(record.totpSecret, 'JBSWY3DPEHPK3PXP')
  assert.match(record.totpUri, /otpauth:/)
})

test('base32 带空格也能识别', () => {
  const record = only('a@b.com----pw----c@d.com----JBSW Y3DP EHPK 3PXP')
  assert.equal(record.totpSecret, 'JBSW Y3DP EHPK 3PXP')
})

test('全大写单词不被当成 TOTP 密钥', () => {
  const record = only('a@b.com----PASSWORD----c@d.com----123456')
  assert.equal(record.password, 'PASSWORD')
  assert.equal(record.totpSecret, '')
})

test('123456 作密码时按位置判为 2FA（明确位置）', () => {
  const record = only('a@b.com----123456----c@d.com')
  assert.equal(record.password, '123456')
  assert.equal(record.code2fa, '')
})

test('整行 --- 是记录分隔线', () => {
  const records = parseAccounts('a@b.com----pw----c@d.com----123456\n---\nd@e.com----pw2----f@g.com----654321')
  assert.equal(records.length, 2)
  assert.equal(records[1].account, 'd@e.com')
})

test('空行分隔多条记录', () => {
  const records = parseAccounts('a@b.com----pw----c@d.com----123456\n\nd@e.com----pw2----f@g.com----654321')
  assert.equal(records.length, 2)
})

test('CSV 转义与公式注入防护', () => {
  const csv = toCsv([
    {
      account: '=cmd',
      password: 'p,w',
      recoveryEmail: '',
      code2fa: '',
      totpSecret: '',
      totpUri: '',
      note: '',
      raw: '',
      fields: {},
    },
  ])
  const lines = csv.split('\n')
  assert.equal(lines.length, 2)
  assert.match(lines[1], /^'=cmd,"p,w"/)
})

test('空输入不产生记录', () => {
  assert.deepEqual(parseAccounts('   \n\n  '), [])
})
