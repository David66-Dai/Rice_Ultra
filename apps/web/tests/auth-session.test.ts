import assert from 'node:assert/strict'
import test, { after, before, type TestContext } from 'node:test'
import { createServer, type ViteDevServer } from 'vite'
import type { LoginResponse } from '../../../packages/shared/src/index.ts'

type SessionModule = typeof import('../src/auth/session.ts')
let vite: ViteDevServer
let session: SessionModule

before(async () => {
  // Transform the real browser modules without opening a listener or reading deployment configuration.
  vite = await createServer({
    configFile: false,
    envDir: false,
    server: { middlewareMode: true, hmr: false, watch: null },
    optimizeDeps: { noDiscovery: true, include: [] },
  })
  assert.equal(vite.httpServer, null)
  session = await vite.ssrLoadModule('/src/auth/session.ts') as SessionModule
})

after(async () => { await vite?.close() })

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
  }
}

function fixture(t: TestContext) {
  const local = memoryStorage()
  const tab = memoryStorage()
  for (const [key, value] of [['localStorage', local], ['sessionStorage', tab]] as const) {
    const previous = Object.getOwnPropertyDescriptor(globalThis, key)
    Object.defineProperty(globalThis, key, { configurable: true, value })
    t.after(() => {
      if (previous) Object.defineProperty(globalThis, key, previous)
      else Reflect.deleteProperty(globalThis, key)
    })
  }
  local.setItem('srs.rememberToken', 'remember-original')
  tab.setItem('srs.accessToken', 'access-expired')
  const calls: {
    url: string
    options: RequestInit
    respond: (status: number, body: unknown) => void
    fail: (error: unknown) => void
  }[] = []
  t.mock.method(globalThis, 'fetch', (input: string | URL | Request, options: RequestInit = {}) =>
    new Promise<Response>((resolve, reject) => {
      const abort = () => reject(new DOMException('aborted', 'AbortError'))
      options.signal?.addEventListener('abort', abort, { once: true })
      calls.push({
        url: String(input),
        options,
        respond(status, body) {
          options.signal?.removeEventListener('abort', abort)
          resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
        },
        fail(error) {
          options.signal?.removeEventListener('abort', abort)
          reject(error)
        },
      })
    }))
  return { local, tab, calls }
}

function loginResponse(suffix = 'rotated'): LoginResponse {
  return {
    tokenType: 'Bearer',
    accessToken: `access-${suffix}`,
    expiresIn: 3600,
    rememberToken: `remember-${suffix}`,
    user: { id: 1, username: 'admin', displayName: '管理员', role: 'USER' },
  }
}

test('concurrent refreshes rotate once, save the result and allow a later fresh rotation', async t => {
  const f = fixture(t)
  const first = session.exchangeRememberToken()
  const second = session.exchangeRememberToken()
  assert.strictEqual(first, second)
  assert.equal(f.calls.length, 1)
  assert.equal(f.calls[0].url, '/api/auth/remember')
  assert.equal(f.calls[0].options.method, 'POST')
  assert.deepEqual(JSON.parse(String(f.calls[0].options.body)), { rememberToken: 'remember-original' })
  f.calls[0].respond(200, loginResponse())
  assert.deepEqual(await first, loginResponse())
  assert.deepEqual(await second, loginResponse())
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-rotated')
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-rotated')
  assert.equal(f.local.getItem('srs.username'), 'admin')

  const later = session.exchangeRememberToken()
  assert.equal(f.calls.length, 2)
  assert.deepEqual(JSON.parse(String(f.calls[1].options.body)), { rememberToken: 'remember-rotated' })
  f.calls[1].respond(200, loginResponse('later'))
  await later
})

test('canceling one waiter promptly rejects only that caller and leaves shared refresh running', async t => {
  const f = fixture(t)
  const controller = new AbortController()
  const canceled = session.exchangeRememberToken(controller.signal)
  const active = session.exchangeRememberToken()
  const rejection = assert.rejects(canceled, { name: 'AbortError' })
  controller.abort()
  await rejection
  assert.equal(f.calls.length, 1)
  assert.equal(f.calls[0].options.signal?.aborted, false)
  f.calls[0].respond(200, loginResponse())
  assert.deepEqual(await active, loginResponse())
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-rotated')
})

test('a previously canceled caller never starts a refresh request', async t => {
  const f = fixture(t)
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(session.exchangeRememberToken(controller.signal), { name: 'AbortError' })
  assert.equal(f.calls.length, 0)
})

test('a hung refresh times out in ten seconds and releases the shared promise for reconnect', async t => {
  const f = fixture(t)
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const first = session.exchangeRememberToken()
  const second = session.exchangeRememberToken(new AbortController().signal)
  const rejections = Promise.all([
    assert.rejects(first, { name: 'AbortError' }),
    assert.rejects(second, { name: 'AbortError' }),
  ])
  t.mock.timers.tick(9_999)
  assert.equal(f.calls[0].options.signal?.aborted, false)
  t.mock.timers.tick(1)
  await rejections
  assert.equal(f.calls[0].options.signal?.aborted, true)
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-original')
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-expired')

  const reconnect = session.exchangeRememberToken()
  assert.equal(f.calls.length, 2)
  f.calls[1].respond(200, loginResponse())
  await reconnect
})

test('a late refresh response cannot restore a logged-out session or replace a new account', async t => {
  const f = fixture(t)
  const pendingLogout = session.exchangeRememberToken()
  f.local.removeItem('srs.rememberToken')
  f.tab.removeItem('srs.accessToken')
  f.calls[0].respond(200, loginResponse())
  assert.equal(await pendingLogout, null)
  assert.equal(f.local.getItem('srs.rememberToken'), null)
  assert.equal(f.tab.getItem('srs.accessToken'), null)

  f.local.setItem('srs.rememberToken', 'remember-original')
  const pendingSwitch = session.exchangeRememberToken()
  f.local.setItem('srs.rememberToken', 'remember-other-user')
  f.tab.setItem('srs.accessToken', 'access-other-user')
  f.calls[1].respond(200, loginResponse())
  assert.equal(await pendingSwitch, null)
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-other-user')
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-other-user')
})

test('network errors propagate for reconnect while invalid refresh credentials are cleared', async t => {
  const f = fixture(t)
  const networkFailure = session.exchangeRememberToken()
  const rejected = assert.rejects(networkFailure, { name: 'NetworkError' })
  f.calls[0].fail(new TypeError('mock offline'))
  await rejected
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-original')
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-expired')

  const expired = session.exchangeRememberToken()
  f.calls[1].respond(401, { code: 'invalid_token', message: '记住登录已失效' })
  assert.equal(await expired, null)
  assert.equal(f.local.getItem('srs.rememberToken'), null)
})

test('a late rejection of old credentials does not clear a replacement account token', async t => {
  const f = fixture(t)
  const stale = session.exchangeRememberToken()
  const rejected = assert.rejects(stale, { name: 'ApiError', status: 403 })
  f.local.setItem('srs.rememberToken', 'remember-other-user')
  f.tab.setItem('srs.accessToken', 'access-other-user')
  f.calls[0].respond(403, { code: 'revoked', message: '旧令牌已失效' })
  await rejected
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-other-user')
  assert.equal(f.tab.getItem('srs.accessToken'), 'access-other-user')
})

test('boot remains anonymous after a transient refresh failure and preserves remembered credentials', async t => {
  const f = fixture(t)
  f.tab.removeItem('srs.accessToken')
  const boot = session.bootOnce()
  f.calls[0].fail(new TypeError('mock offline'))
  const result = await boot
  assert.equal(result.status, 'anonymous')
  assert.equal(f.local.getItem('srs.rememberToken'), 'remember-original')
})
