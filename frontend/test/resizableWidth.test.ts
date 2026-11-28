import assert from 'node:assert/strict'
import test from 'node:test'
import { createResizableWidth } from '../src/lib/resizableWidth.ts'

function installBrowser() {
  const storage = new Map<string, string>()
  const listeners = new Map<string, Set<(event: PointerEvent) => void>>()
  const classes = new Set<string>()

  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
      removeItem: (key: string) => void storage.delete(key),
    },
  })
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      body: {
        classList: {
          add: (name: string) => void classes.add(name),
          remove: (name: string) => void classes.delete(name),
        },
      },
    },
  })
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      innerWidth: 1200,
      addEventListener: (name: string, fn: (event: PointerEvent) => void) => {
        if (!listeners.has(name)) listeners.set(name, new Set())
        listeners.get(name)?.add(fn)
      },
      removeEventListener: (name: string, fn: (event: PointerEvent) => void) => {
        listeners.get(name)?.delete(fn)
      },
    },
  })

  return {
    storage,
    classes,
    dispatch(name: string, x: number) {
      for (const fn of listeners.get(name) ?? []) fn({ clientX: x, preventDefault: () => {} } as PointerEvent)
    },
    listenerCount(name: string) {
      return listeners.get(name)?.size ?? 0
    },
  }
}

function event(x: number): PointerEvent {
  return { clientX: x, preventDefault: () => {} } as PointerEvent
}

test('drag applies clamped width, stores it on pointerup, and cleans listeners', () => {
  const browser = installBrowser()
  const applied: Array<number | null> = []
  const controller = createResizableWidth({
    storageKey: 'w',
    minWidth: 200,
    maxWidth: 500,
    viewportReserve: 400,
    minViewportWidth: 700,
    resizingClass: 'resizing',
    measureWidth: (x) => x,
    setWidth: (value) => applied.push(value),
  })

  controller.pointerDown(event(240))
  assert.ok(browser.classes.has('resizing'))
  browser.dispatch('pointermove', 900)
  assert.equal(controller.width, 500)
  browser.dispatch('pointerup', 900)

  assert.deepEqual(applied, [500])
  assert.equal(browser.storage.get('w'), '500')
  assert.equal(browser.listenerCount('pointermove'), 0)
  assert.equal(browser.classes.has('resizing'), false)
})

test('restore clamps saved width and reset emits the caller-visible default signal', () => {
  const browser = installBrowser()
  browser.storage.set('w', '20')
  const applied: Array<number | null> = []
  const controller = createResizableWidth({
    storageKey: 'w',
    minWidth: 200,
    maxWidth: 500,
    viewportReserve: 400,
    minViewportWidth: 700,
    resizingClass: 'resizing',
    measureWidth: (x) => x,
    setWidth: (value) => applied.push(value),
  })

  controller.restore()
  assert.equal(controller.width, 200)

  controller.reset()
  assert.deepEqual(applied, [200, null])
  assert.equal(browser.storage.has('w'), false)
})

test('small viewports do not start dragging and destroy removes drag state', () => {
  const browser = installBrowser()
  ;(window as unknown as { innerWidth: number }).innerWidth = 600
  const controller = createResizableWidth({
    storageKey: 'w',
    minWidth: 200,
    maxWidth: 500,
    viewportReserve: 400,
    minViewportWidth: 700,
    resizingClass: 'resizing',
    measureWidth: (x) => x,
    setWidth: () => {},
  })

  controller.pointerDown(event(240))
  assert.equal(browser.listenerCount('pointermove'), 0)
  assert.equal(browser.classes.has('resizing'), false)

  ;(window as unknown as { innerWidth: number }).innerWidth = 1200
  controller.pointerDown(event(240))
  controller.destroy()
  assert.equal(browser.listenerCount('pointermove'), 0)
  assert.equal(browser.classes.has('resizing'), false)
})
