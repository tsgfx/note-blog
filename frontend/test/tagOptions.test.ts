import assert from 'node:assert/strict'
import test from 'node:test'
import { visibleTagOptions } from '../src/lib/tagOptions.ts'

const tags = [
  { name: 'Java', count: 30 },
  { name: '数据库', count: 20 },
  { name: '点云', count: 10 },
  { name: '三维视觉', count: 8 },
  { name: '冷僻', count: 1 },
]

test('selected tags stay first even when outside the hot-tag window', () => {
  const result = visibleTagOptions(tags, ['冷僻'], '', false, 2)
  assert.deepEqual(
    result.map((tag) => tag.name),
    ['冷僻', 'Java', '数据库'],
  )
})

test('search shows every match regardless of collapsed limit', () => {
  const result = visibleTagOptions(tags, [], '视', false, 1)
  assert.deepEqual(
    result.map((tag) => tag.name),
    ['三维视觉'],
  )
})

test('expanded state keeps all unselected tags after selected tags', () => {
  const result = visibleTagOptions(tags, ['点云'], '', true, 1)
  assert.deepEqual(
    result.map((tag) => tag.name),
    ['点云', 'Java', '数据库', '三维视觉', '冷僻'],
  )
})

test('collapsed state fills the remaining window by existing hot order', () => {
  const result = visibleTagOptions(tags, [], '', false, 3)
  assert.deepEqual(
    result.map((tag) => tag.name),
    ['Java', '数据库', '点云'],
  )
})
