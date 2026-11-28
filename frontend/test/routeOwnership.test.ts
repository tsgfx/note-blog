import assert from 'node:assert/strict'
import test from 'node:test'

test('parameterized client routes are constructed through named routes', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join, relative } = await import('node:path')
  const offenders: string[] = []
  const root = join(process.cwd(), 'src')
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) {
        walk(path)
      } else if (/\.(ts|vue)$/.test(path) && !relative(root, path).startsWith('api/')) {
        const text = readFileSync(path, 'utf8')
        if (/`\/(blog|notes|editor)\/\$\{/.test(text)) offenders.push(path)
      }
    }
  }
  walk(root)
  assert.deepEqual(offenders, [])
})
