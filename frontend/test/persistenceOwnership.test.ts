import assert from 'node:assert/strict'
import test from 'node:test'

test('owned persistence keys are not referenced outside their canonical modules', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join } = await import('node:path')
  const owners = new Map([
    ['ask-quota', 'lib/guestQuota.ts'],
    ['notes_blog_theme', 'lib/theme.ts'],
  ])
  const offenders: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (/\.(ts|vue)$/.test(path)) {
        const text = readFileSync(path, 'utf8')
        for (const [key, owner] of owners) {
          if (!path.endsWith(owner) && text.includes(key)) offenders.push(`${path}: ${key}`)
        }
      }
    }
  }
  walk(join(process.cwd(), 'src'))
  assert.deepEqual(offenders, [])
})
