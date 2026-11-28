import assert from 'node:assert/strict'
import test from 'node:test'
import { insertBlockAtCursor, prefixSelectedLines, surroundSelection } from '../src/lib/sourceTextEdit.ts'

test('insertBlockAtCursor inserts a standalone block at the caret', () => {
  const result = insertBlockAtCursor('abc正文', 3, 3, '![图](x.png)')
  assert.equal(result.value, 'abc\n![图](x.png)\n正文')
  assert.equal(result.selectionStart, 'abc\n![图](x.png)\n'.length)
  assert.equal(result.selectionEnd, result.selectionStart)
})

test('insertBlockAtCursor does not add a duplicate leading newline', () => {
  const result = insertBlockAtCursor('abc\n', 4, 4, 'block')
  assert.equal(result.value, 'abc\nblock\n')
  assert.equal(result.selectionStart, 'abc\nblock\n'.length)
})

test('surroundSelection wraps the current selection and selects the wrapped text', () => {
  const result = surroundSelection('hello world', 6, 11, '**', '**', '粗体')
  assert.equal(result.value, 'hello **world**')
  assert.equal(result.selectionStart, 8)
  assert.equal(result.selectionEnd, 13)
})

test('surroundSelection inserts and selects placeholder when there is no selection', () => {
  const result = surroundSelection('abc', 3, 3, '`', '`', 'code')
  assert.equal(result.value, 'abc`code`')
  assert.equal(result.selectionStart, 4)
  assert.equal(result.selectionEnd, 8)
})

test('prefixSelectedLines prefixes each selected line without duplicating an existing prefix', () => {
  const result = prefixSelectedLines('one\ntwo\n- three', 0, 'one\ntwo\n- three'.length, '- ')
  assert.equal(result.value, '- one\n- two\n- three')
  assert.equal(result.selectionStart, 0)
  assert.equal(result.selectionEnd, '- one\n- two\n- three'.length)
})

test('prefixSelectedLines starts at the containing line but preserves partial-line end semantics', () => {
  const result = prefixSelectedLines('aa\nbbcc', 4, 5, '> ')
  assert.equal(result.value, 'aa\n> bbcc')
  assert.equal(result.selectionStart, 3)
  assert.equal(result.selectionEnd, 7)
})
