import assert from 'node:assert/strict'
import test from 'node:test'
import { parseAskStreamEvent } from '../src/lib/askStream.ts'

test('parses the data line of an SSE block', () => {
  assert.deepEqual(parseAskStreamEvent('event: delta\ndata: {"type":"delta","data":{"text":"hi"}}'), {
    type: 'delta',
    data: { text: 'hi' },
  })
})

test('missing or malformed data yields no event', () => {
  assert.equal(parseAskStreamEvent('event: ping'), null)
  assert.equal(parseAskStreamEvent('data: not-json'), null)
})

test('missing data object defaults to empty object', () => {
  assert.deepEqual(parseAskStreamEvent('data: {"type":"done"}'), { type: 'done', data: {} })
})
