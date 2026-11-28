import assert from 'node:assert/strict'
import test from 'node:test'
import { hasBlockingFinding, publishablePreflightIds, warningPreflightIds } from '../src/lib/preflight.ts'
import type { PreflightReport } from '../src/api/notes.ts'

const report: PreflightReport = {
  blockers: 1,
  warnings: 2,
  items: [
    { id: 1, title: 'clean', findings: [] },
    { id: 2, title: 'blocked', findings: [{ level: 'BLOCK', code: 'empty', message: '正文为空' }] },
    { id: 3, title: 'warn', findings: [{ level: 'WARN', code: 'secret', message: '疑似密钥' }] },
    { id: 4, title: 'also warn', findings: [{ level: 'WARN', code: 'meta', message: '缺摘要' }] },
  ],
}

test('blocking findings are identified per item', () => {
  assert.equal(hasBlockingFinding(report.items[0]), false)
  assert.equal(hasBlockingFinding(report.items[1]), true)
})

test('warning ids include every warn item regardless of manual exclusion', () => {
  assert.deepEqual(warningPreflightIds(report), [3, 4])
})

test('publishable ids exclude blockers and manually excluded items', () => {
  assert.deepEqual(publishablePreflightIds(report, new Set()), [1, 3, 4])
  assert.deepEqual(publishablePreflightIds(report, new Set([4])), [1, 3])
})
