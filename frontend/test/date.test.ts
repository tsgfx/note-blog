import assert from 'node:assert/strict'
import test from 'node:test'
import { isoDate, isoYear, monthDayCompact, monthDaySpaced } from '../src/lib/date.ts'

test('isoDate and isoYear preserve the existing ISO display semantics', () => {
  assert.equal(isoDate('2026-10-26T09:00:00'), '2026-10-26')
  assert.equal(isoYear('2026-10-26T09:00:00'), '2026')
  assert.equal(isoDate(undefined), '')
  assert.equal(isoYear(undefined), '')
})

test('month-day variants preserve page-specific display formats', () => {
  assert.equal(monthDayCompact('2026-10-26T09:00:00'), '10-26')
  assert.equal(monthDaySpaced('2026-10-26T09:00:00'), '10 / 26')
  assert.equal(monthDayCompact(undefined), '')
  assert.equal(monthDaySpaced(undefined), '')
})
