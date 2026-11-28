import assert from 'node:assert/strict'
import test from 'node:test'
import { notesListKeyAction } from '../src/lib/notesListKeyboard.ts'

const base = { key: '', typing: false, hasQuery: false, dialogOpen: false, modified: false, focused: false }

test('typing owns keys except Escape, which clears query or blurs search', () => {
  assert.equal(notesListKeyAction({ ...base, key: 'j', typing: true }), 'none')
  assert.equal(notesListKeyAction({ ...base, key: 'Escape', typing: true, hasQuery: true }), 'clear-query-and-blur')
  assert.equal(notesListKeyAction({ ...base, key: 'Escape', typing: true }), 'blur-search')
})

test('dialog and modifier keys disable list shortcuts', () => {
  assert.equal(notesListKeyAction({ ...base, key: 'j', dialogOpen: true }), 'none')
  assert.equal(notesListKeyAction({ ...base, key: 'j', modified: true }), 'none')
})

test('navigation, opening, selection and clearing follow focused row state', () => {
  assert.equal(notesListKeyAction({ ...base, key: '/' }), 'focus-search')
  assert.equal(notesListKeyAction({ ...base, key: 'j' }), 'move-focus-down')
  assert.equal(notesListKeyAction({ ...base, key: 'ArrowUp' }), 'move-focus-up')
  assert.equal(notesListKeyAction({ ...base, key: 'Enter', focused: true }), 'open-focused')
  assert.equal(notesListKeyAction({ ...base, key: 'Enter' }), 'none')
  assert.equal(notesListKeyAction({ ...base, key: 'x', focused: true }), 'toggle-focused-selection')
  assert.equal(notesListKeyAction({ ...base, key: 'Escape' }), 'clear-focus-and-selection')
})
