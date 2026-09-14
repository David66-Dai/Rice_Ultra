import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import type { DeviceSyncResponse, PlatformNotification } from '../../../packages/shared/src/index.ts'
import { createDeviceSyncSession, type DeviceSyncView } from '../src/lib/device-sync.ts'

type Request = Parameters<typeof createDeviceSyncSession>[0]
type PendingRequest = {
  path: string
  options: NonNullable<Parameters<Request>[1]>
  resolve: (value: unknown) => void
  reject: (reason: unknown) => void
}

function transport() {
  const calls: PendingRequest[] = []
  // Deliberately allow responses after abort, as a transport or parser may finish late.
  const request: Request = <T>(path: string, options: PendingRequest['options'] = {}) =>
    new Promise<T>((resolve, reject) => {
      calls.push({ path, options, resolve: value => resolve(value as T), reject })
    })
  return {
    request,
    calls,
    at(index: number) {
      const call = calls[index]
      assert.ok(call, `expected request ${index}; received ${calls.length}`)
      return call
    },
    posts: () => calls.filter(call => call.options.method === 'POST'),
  }
}

function notification(id: number): PlatformNotification {
  return {
    id,
    type: 'device_control',
    message: 'admin用户开启驱虫灯功能',
    createdAt: '2026-09-11T00:00:00Z',
    stationId: 'station-1',
    actorUsername: 'admin',
    actorDisplayName: '管理员',
    device: 'lamp',
    enabled: true,
  }
}

function snapshot(overrides: Partial<DeviceSyncResponse> = {}): DeviceSyncResponse {
  return {
    cursor: 'revision:7/read:0',
    canControl: true,
    available: true,
    devices: [{
      stationId: 'station-1',
      device: 'lamp',
      enabled: false,
      revision: 7,
      updatedAt: null,
      updatedBy: null,
    }],
    preventionPolicy: {
      requireAstrBotConfirmation: true,
      revision: 0,
      updatedAt: '2026-09-12T00:00:00Z',
      updatedBy: null,
      spraySafetyEnabled: true,
      maxSprayWindSpeedMs: 3,
      sensorMaxAgeSeconds: 120,
      leafEvidenceMaxAgeSeconds: 86400,
    },
    notifications: [],
    unreadCount: 0,
    ...overrides,
  }
}

function setup(t: TestContext) {
  const network = transport()
  const views: DeviceSyncView[] = []
  const session = createDeviceSyncSession(network.request, view => views.push(view))
  t.after(() => session.dispose())
  return {
    network,
    session,
    views,
    view() {
      const view = views.at(-1)
      assert.ok(view, 'expected a published view')
      return view
    },
  }
}

async function settle() {
  for (let index = 0; index < 6; index += 1) await Promise.resolve()
}

test('two authenticated sessions receive a control result and notification without page reload', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const admin = setup(t)
  const observer = setup(t)
  admin.session.start()
  observer.session.start()
  admin.network.at(0).resolve(snapshot())
  observer.network.at(0).resolve(snapshot({ canControl: false }))
  await settle()

  t.mock.timers.tick(50)
  assert.equal(admin.network.at(1).path, '/api/devices/sync?after=revision%3A7%2Fread%3A0&waitSeconds=25')
  assert.equal(observer.network.at(1).path, admin.network.at(1).path)

  const command = admin.session.controlDevice('station-1', 'lamp', true)
  const post = admin.network.at(2)
  assert.equal(post.path, '/api/devices/control')
  assert.deepEqual(post.options.body, {
    stationId: 'station-1', device: 'lamp', enabled: true, expectedRevision: 7,
  })
  assert.equal(admin.view().snapshot?.devices[0].enabled, false)

  const next = snapshot({
    cursor: 'revision:8/read:0',
    devices: [{ ...snapshot().devices[0], enabled: true, revision: 8, updatedBy: 'admin' }],
    notifications: [notification(1)],
    unreadCount: 1,
  })
  post.resolve({ state: next.devices[0] })
  await command
  assert.equal(admin.network.at(1).options.signal?.aborted, true)
  admin.network.at(3).resolve(next)
  observer.network.at(1).resolve({ ...next, canControl: false })
  await settle()

  for (const client of [admin, observer]) {
    assert.equal(client.view().connection, 'connected')
    assert.equal(client.view().snapshot?.devices[0].enabled, true)
    assert.equal(client.view().snapshot?.devices[0].revision, 8)
    assert.equal(client.view().snapshot?.notifications[0].message, 'admin用户开启驱虫灯功能')
    assert.equal(client.view().snapshot?.unreadCount, 1)
  }
  assert.equal(observer.network.posts().length, 0)
})

test('observer, unavailable, unsynchronized, disconnected and unsupported controls never POST', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const cases = [
    { value: snapshot({ canControl: false }), error: /没有设备控制权限/ },
    { value: snapshot({ available: false }), error: /链路当前不可用/ },
    { value: snapshot({ devices: [] }), error: /不支持此设备/ },
  ]
  for (const { value, error } of cases) {
    const client = setup(t)
    client.session.start()
    client.network.at(0).resolve(value)
    await settle()
    await assert.rejects(client.session.controlDevice('station-1', 'lamp', true), error)
    assert.equal(client.network.posts().length, 0)
    client.session.dispose()
  }

  const disconnected = setup(t)
  disconnected.session.start()
  await assert.rejects(disconnected.session.controlDevice('station-1', 'lamp', true), /尚未同步/)
  disconnected.network.at(0).resolve(snapshot())
  await settle()
  t.mock.timers.tick(50)
  disconnected.network.at(1).reject(new Error('offline'))
  await settle()
  assert.equal(disconnected.view().connection, 'reconnecting')
  await assert.rejects(disconnected.session.controlDevice('station-1', 'lamp', true), /尚未同步/)
  assert.equal(disconnected.network.posts().length, 0)
})

test('a conflicting command preserves the observed state and immediately fetches authoritative state', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  const original = snapshot()
  client.session.start()
  client.network.at(0).resolve(original)
  await settle()

  const command = client.session.controlDevice('station-1', 'lamp', true)
  const rejected = assert.rejects(command, /状态已更新/)
  client.network.at(1).reject(Object.assign(new Error('状态已更新，请重试'), { status: 409 }))
  await rejected
  assert.strictEqual(client.view().snapshot, original)
  assert.equal(client.network.at(2).path, '/api/devices/sync')
  assert.equal(client.network.posts().length, 1)

  const authoritative = snapshot({
    cursor: 'revision:9',
    devices: [{ ...original.devices[0], revision: 9, updatedBy: 'dzh' }],
  })
  client.network.at(2).resolve(authoritative)
  await settle()
  assert.strictEqual(client.view().snapshot, authoritative)
  assert.equal(client.view().snapshot?.devices[0].enabled, false)
})

test('a canceled long poll cannot overwrite a newer refresh or schedule another poll', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  t.mock.timers.tick(50)
  const stale = client.network.at(1)

  client.session.refresh()
  assert.equal(stale.options.signal?.aborted, true)
  const fresh = snapshot({ cursor: 'newer' })
  client.network.at(2).resolve(fresh)
  await settle()
  const published = client.views.length
  stale.resolve(snapshot({ cursor: 'obsolete' }))
  await settle()
  assert.strictEqual(client.view().snapshot, fresh)
  assert.equal(client.views.length, published)

  t.mock.timers.tick(50)
  assert.equal(client.network.calls.length, 4)
  assert.equal(client.network.at(3).path, '/api/devices/sync?after=newer&waitSeconds=25')
})

test('logout disposal aborts active polls and ignores late results and refresh calls', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  t.mock.timers.tick(50)
  const poll = client.network.at(1)
  const published = client.views.length

  client.session.dispose()
  assert.equal(poll.options.signal?.aborted, true)
  poll.resolve(snapshot({ cursor: 'after-logout' }))
  await settle()
  client.session.refresh()
  client.session.start()
  t.mock.timers.tick(60_000)
  await settle()
  assert.equal(client.network.calls.length, 2)
  assert.equal(client.views.length, published)
  await assert.rejects(client.session.controlDevice('station-1', 'lamp', true), /会话已结束/)
  assert.equal(client.network.posts().length, 0)
})

test('logout disposal aborts pending mutations and cancels scheduled polling', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  const command = client.session.controlDevice('station-1', 'lamp', true)
  const pending = client.network.at(1)
  const published = client.views.length

  client.session.dispose()
  assert.equal(pending.options.signal?.aborted, true)
  pending.resolve({ state: snapshot().devices[0] })
  await command
  t.mock.timers.tick(60_000)
  await settle()
  assert.equal(client.network.calls.length, 2)
  assert.equal(client.views.length, published)
})

test('an uncertain command timeout refreshes state without retrying the POST', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  const command = client.session.controlDevice('station-1', 'lamp', true)
  const rejected = assert.rejects(command, /结果尚未确认/)
  const pending = client.network.at(1)
  pending.options.signal?.addEventListener('abort', () => {
    pending.reject(Object.assign(new Error('request aborted'), { name: 'AbortError' }))
  }, { once: true })

  t.mock.timers.tick(15_000)
  await rejected
  assert.equal(pending.options.signal?.aborted, true)
  assert.equal(client.network.posts().length, 1)
  const refresh = client.network.calls.at(-1)!
  assert.equal(refresh.path, '/api/devices/sync')
  refresh.resolve(snapshot({ cursor: 'after-timeout' }))
  await settle()
  t.mock.timers.tick(50)
  await settle()
  assert.equal(client.network.posts().length, 1)
  assert.equal(client.view().snapshot?.devices[0].enabled, false)
})

test('a second control is rejected while the first command is in flight', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  const command = client.session.controlDevice('station-1', 'lamp', true)
  await assert.rejects(client.session.controlDevice('station-1', 'lamp', false), /上一条控制指令/)
  assert.equal(client.network.posts().length, 1)
  client.network.at(1).resolve({ state: snapshot().devices[0] })
  await command
})

test('confirmation policy toggle posts the synchronized revision and refreshes without retrying', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  const update = client.session.setDiagnosisConfirmationRequired(false)
  const post = client.network.at(1)
  assert.equal(post.path, '/api/devices/prevention-policy')
  assert.deepEqual(post.options.body, { requireAstrBotConfirmation: false, expectedRevision: 0 })
  post.resolve({ requireAstrBotConfirmation: false, revision: 1 })
  await update
  assert.equal(client.network.at(2).path, '/api/devices/sync')
  assert.equal(client.network.posts().length, 1)
})

test('markRead uses the current maximum notification id and refreshes only its own session', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const first = setup(t)
  const second = setup(t)
  const initial = snapshot({ notifications: [notification(3), notification(8), notification(6)], unreadCount: 3 })
  first.session.start()
  second.session.start()
  first.network.at(0).resolve(initial)
  second.network.at(0).resolve({ ...initial, canControl: false })
  await settle()
  const read = first.session.markRead()
  assert.equal(first.network.at(1).path, '/api/notifications/read')
  assert.deepEqual(first.network.at(1).options.body, { throughId: 8 })
  assert.equal(second.network.posts().length, 0)
  assert.equal(first.view().snapshot?.unreadCount, 3)

  first.network.at(1).resolve(undefined)
  await read
  first.network.at(2).resolve(snapshot({
    cursor: 'revision:9/read:8',
    notifications: [notification(9), ...initial.notifications],
    unreadCount: 1,
  }))
  await settle()
  assert.equal(first.view().snapshot?.unreadCount, 1)
  assert.equal(second.view().snapshot?.unreadCount, 3)
  assert.deepEqual(first.network.at(1).options.body, { throughId: 8 })

  const secondRead = second.session.markRead()
  assert.deepEqual(second.network.at(1).options.body, { throughId: 8 })
  second.network.at(1).resolve(undefined)
  await secondRead
})

test('markRead does not send a request before sync or for an empty notification list', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const client = setup(t)
  await client.session.markRead()
  assert.equal(client.network.calls.length, 0)
  client.session.start()
  client.network.at(0).resolve(snapshot())
  await settle()
  await client.session.markRead()
  assert.equal(client.network.posts().length, 0)
})
