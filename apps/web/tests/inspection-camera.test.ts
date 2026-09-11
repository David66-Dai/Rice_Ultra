import assert from 'node:assert/strict'
import test from 'node:test'
import { createCameraSession } from '../src/lib/inspection-camera.ts'

function device(id: string, kind = 'videoinput') {
  return { deviceId: id, kind, label: `Camera ${id}` }
}

function makeStream(id: string) {
  const track = Object.assign(new EventTarget(), {
    stopped: false,
    getSettings: () => ({ deviceId: id }),
    stop() { this.stopped = true },
  })
  return { track, getTracks: () => [track], getVideoTracks: () => [track] }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function fixture() {
  const snapshots: any[] = []
  const calls: any[] = []
  const streams: ReturnType<typeof makeStream>[] = []
  let devices = [device('a'), device('b')]
  const media = Object.assign(new EventTarget(), {
    enumerateDevices: async () => devices,
    async getUserMedia(constraints: any) {
      calls.push(constraints)
      const stream = makeStream(constraints.video.deviceId?.exact ?? 'a')
      streams.push(stream)
      return stream
    },
  })
  const session = createCameraSession(media as any, state => snapshots.push(state))
  return {
    media, session, calls, streams, snapshots,
    state: () => snapshots.at(-1),
    setDevices: (next: typeof devices) => { devices = next },
  }
}

const tick = () => new Promise(resolve => setImmediate(resolve))

test('discovers every video input after permission and opens the second by exact ID', async () => {
  const f = fixture()
  f.setDevices([device('a'), device('mic', 'audioinput'), device('b'), device('c'), device('d'), device('b')])
  await f.session.start()
  assert.deepEqual(f.state().devices.map((d: any) => d.deviceId), ['a', 'b', 'c', 'd'])
  await f.session.select('b')
  assert.equal(f.streams[0].track.stopped, true)
  assert.deepEqual(f.calls[1].video.deviceId, { exact: 'b' })
  assert.equal(f.calls[1].audio, false)
  assert.equal(f.state().selectedDeviceId, 'b')
  assert.equal(f.state().status, 'live')
  f.session.dispose()
  assert.equal(f.streams[1].track.stopped, true)
})

test('permission reveals additional cameras and actual default camera stays selected', async () => {
  const f = fixture()
  f.media.enumerateDevices = async () => f.calls.length ? [device('b'), device('a')] : [device('')]
  await f.session.start()
  assert.equal(f.state().devices.length, 2)
  assert.equal(f.state().selectedDeviceId, 'a')
  f.session.dispose()
})

test('devicechange adds cameras and reordering preserves the selected stream', async () => {
  const f = fixture()
  await f.session.start()
  await f.session.select('b')
  f.setDevices([device('c'), device('b'), device('a')])
  f.media.dispatchEvent(new Event('devicechange'))
  await tick()
  assert.equal(f.state().devices.length, 3)
  assert.equal(f.state().selectedDeviceId, 'b')
  assert.equal(f.calls.length, 2)
  f.session.dispose()
})

test('unplugging selected camera falls back; zero cameras and reconnection recover', async () => {
  const f = fixture()
  await f.session.start()
  f.setDevices([device('b')])
  f.media.dispatchEvent(new Event('devicechange'))
  await tick()
  assert.equal(f.state().selectedDeviceId, 'b')
  assert.equal(f.streams[0].track.stopped, true)
  f.setDevices([])
  await f.session.refresh()
  assert.equal(f.state().stream, null)
  assert.equal(f.state().status, 'unavailable')
  f.setDevices([device('c')])
  await f.session.refresh()
  assert.equal(f.state().selectedDeviceId, 'c')
  assert.equal(f.state().status, 'live')
  f.session.dispose()
})

test('ended track clears its stream and discovers a remaining camera', async () => {
  const f = fixture()
  await f.session.start()
  f.setDevices([device('b')])
  f.streams[0].track.dispatchEvent(new Event('ended'))
  await tick()
  assert.equal(f.state().selectedDeviceId, 'b')
  assert.equal(f.streams[0].track.stopped, true)
  f.session.dispose()
})

test('out-of-order opens cannot replace latest selection or leak streams', async () => {
  const f = fixture()
  await f.session.start()
  const pending = deferred<ReturnType<typeof makeStream>>()
  const originalOpen = f.media.getUserMedia
  f.media.getUserMedia = constraints => constraints.video.deviceId?.exact === 'b' ? pending.promise : originalOpen(constraints)
  const second = f.session.select('b')
  await f.session.select('a')
  const stale = makeStream('b')
  pending.resolve(stale)
  await second
  assert.equal(stale.track.stopped, true)
  assert.equal(f.state().selectedDeviceId, 'a')
  f.session.dispose()
})

test('unmount while permission is pending stops the late stream and removes listeners', async () => {
  const f = fixture()
  const pending = deferred<ReturnType<typeof makeStream>>()
  f.media.getUserMedia = () => pending.promise
  const starting = f.session.start()
  f.session.dispose()
  const count = f.snapshots.length
  const late = makeStream('a')
  pending.resolve(late)
  await starting
  f.media.dispatchEvent(new Event('devicechange'))
  await tick()
  assert.equal(late.track.stopped, true)
  assert.equal(f.snapshots.length, count)
})

test('denied permission is reported and explicit retry recovers', async () => {
  const f = fixture()
  const originalOpen = f.media.getUserMedia
  f.media.getUserMedia = async () => { throw new DOMException('denied', 'NotAllowedError') }
  await f.session.start()
  assert.equal(f.state().status, 'denied')
  f.media.getUserMedia = originalOpen
  await f.session.retry()
  assert.equal(f.state().status, 'live')
  f.session.dispose()
})

test('busy default camera still lists the second camera for selection', async () => {
  const f = fixture()
  const originalOpen = f.media.getUserMedia
  f.media.getUserMedia = async constraints => {
    if (!constraints.video.deviceId) throw new DOMException('busy', 'NotReadableError')
    return originalOpen(constraints)
  }
  await f.session.start()
  assert.equal(f.state().status, 'error')
  assert.equal(f.state().devices.length, 2)
  await f.session.select('b')
  assert.equal(f.state().status, 'live')
  f.session.dispose()
})

test('enumeration failure does not misreport or stop a working video stream', async () => {
  const f = fixture()
  f.media.enumerateDevices = async () => { throw new Error('enumeration failed') }
  await f.session.start()
  assert.equal(f.state().status, 'live')
  assert.equal(f.streams[0].track.stopped, false)
  assert.match(f.state().message, /列表读取失败/)
  f.session.dispose()
})
