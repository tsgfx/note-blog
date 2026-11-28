import assert from 'node:assert/strict'
import test from 'node:test'
import { noteFolderLabel } from '../src/lib/noteFolder.ts'

test('missing and root-level notes have explicit labels', () => {
  assert.equal(noteFolderLabel(null, ''), '新建笔记')
  assert.equal(noteFolderLabel(undefined, ''), '新建笔记')
  assert.equal(noteFolderLabel('', ''), '新建笔记')
  assert.equal(noteFolderLabel('单文件.md', ''), '根目录')
})

test('nested paths use slash-separated folder names without filename', () => {
  assert.equal(noteFolderLabel('c++/项目A/指针.md', ''), 'c++ / 项目A')
  assert.equal(noteFolderLabel('科研/VLA/note.md', ''), '科研 / VLA')
})

test('active path collapses to a relative folder label', () => {
  assert.equal(noteFolderLabel('c++/项目A/指针.md', 'c++'), '项目A')
  assert.equal(noteFolderLabel('c++/项目A/指针.md', 'c++/项目A'), '本目录')
  assert.equal(noteFolderLabel('c++/项目A/深层/指针.md', 'c++'), '项目A / 深层')
})

test('notes outside the active path remain fully qualified', () => {
  assert.equal(noteFolderLabel('科研/VLA/note.md', 'c++'), '科研 / VLA')
  assert.equal(noteFolderLabel('c/项目A/note.md', 'c++'), 'c / 项目A')
})
