import assert from 'node:assert/strict'
import test from 'node:test'
import { inferQuickCaptureTitle } from '../src/lib/quickCaptureTitle.ts'

test('explicit title wins and is trimmed', () => {
  assert.equal(inferQuickCaptureTitle('正文', '  标题  '), '标题')
})

test('first meaningful markdown line becomes title', () => {
  assert.equal(inferQuickCaptureTitle('\n# \n- [ ] 真正任务\n其他'), '真正任务')
})

test('long inferred title is truncated at forty characters', () => {
  const title = inferQuickCaptureTitle('一'.repeat(45))
  assert.equal(title.length, 41)
  assert.equal(title.endsWith('…'), true)
})

test('empty content falls back to timestamp title', () => {
  const now = new Date(2024, 0, 2, 3, 4)
  assert.equal(inferQuickCaptureTitle('\n# \n', undefined, now), '闪念 2024-01-02 03:04')
})
