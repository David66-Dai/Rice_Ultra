import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildScanOrigins,
  discoveredCamera,
  inferCameraKind,
  isNetworkCameraId,
  loadNetworkCameras,
  manualCamera,
  mergeNetworkCamera,
  mixedContentWarning,
  parseHostRange,
  parsePorts,
  saveNetworkCameras,
  scanNetworkCameras,
  withCacheBuster,
  type NetworkCamera,
  type ScanProbes,
  type ScanProgress,
} from '../src/lib/network-camera.ts'

function memoryStorage(seed: Record<string, string> = {}) {
  const map = new Map(Object.entries(seed))
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => { map.set(key, value) },
    dump: () => Object.fromEntries(map),
  }
}

test('网段支持 CIDR、区间、整段与单机写法', () => {
  assert.deepEqual(parseHostRange('192.168.1.64'), ['192.168.1.64'])
  assert.deepEqual(parseHostRange('10.0.0.5-7'), ['10.0.0.5', '10.0.0.6', '10.0.0.7'])
  assert.equal(parseHostRange('192.168.1.0/24').length, 254)
  assert.equal(parseHostRange('192.168.1.').length, 254)
  assert.deepEqual(parseHostRange('192.168.1.0/30'), ['192.168.1.1', '192.168.1.2'])
  assert.deepEqual(parseHostRange('192.168.1.64, 192.168.1.64 cam.local'), ['192.168.1.64', 'cam.local'])
})

test('非法网段与超大范围会被拦截', () => {
  assert.throws(() => parseHostRange(''), /请填写要扫描的网段/)
  assert.throws(() => parseHostRange('192.168.300.1'), /网段格式不正确/)
  assert.throws(() => parseHostRange('192.168.1.64/snapshot.jpg'), /无法识别的地址/)
  assert.throws(() => parseHostRange('10.0.0.0/16'), /仅支持 \/24 至 \/32/)
  assert.throws(() => parseHostRange('192.168.1.0/24 192.168.2.0/24', 300), /扫描范围过大/)
})

test('端口留空回落默认值，非法端口报错', () => {
  assert.deepEqual(parsePorts(''), [80, 8080, 8000, 81])
  assert.deepEqual(parsePorts('80, 8080 8080'), [80, 8080])
  assert.deepEqual(parsePorts('8000-8002'), [8000, 8001, 8002])
  assert.throws(() => parsePorts('70000'), /端口不正确/)
  assert.throws(() => parsePorts('1-20'), /端口过多/)
})

test('扫描地址按端口展开，80 端口不带端口号', () => {
  assert.deepEqual(buildScanOrigins(['192.168.1.5'], [80, 8080]), [
    'http://192.168.1.5',
    'http://192.168.1.5:8080',
  ])
})

test('手动地址补全协议并推断画面类型', () => {
  const snapshot = manualCamera('192.168.1.64/snapshot.jpg')
  assert.equal(snapshot.streamUrl, 'http://192.168.1.64/snapshot.jpg')
  assert.equal(snapshot.kind, 'snapshot')
  assert.equal(isNetworkCameraId(snapshot.id), true)
  assert.equal(manualCamera('http://192.168.1.9:8080/video').kind, 'mjpeg')
  assert.equal(manualCamera('http://192.168.1.9/still', 'mjpeg').kind, 'mjpeg')
  assert.equal(inferCameraKind('http://cam/axis-cgi/mjpg/video.cgi'), 'mjpeg')
})

test('RTSP 与空地址给出可执行的提示', () => {
  assert.throws(() => manualCamera('rtsp://192.168.1.64:554/stream1'), /浏览器无法直接播放 RTSP/)
  assert.throws(() => manualCamera('   '), /请填写网络摄像头地址/)
})

test('重复添加只更新同一台，手动命名不会被重新扫描覆盖', () => {
  const manual = manualCamera('http://192.168.1.64/snapshot.jpg', 'snapshot', '田埂东侧')
  const list = mergeNetworkCamera([], manual)
  assert.equal(list.length, 1)
  const rediscovered: NetworkCamera = {
    ...manual,
    name: '192.168.1.64 · 通用快照',
    vendor: '通用快照',
    source: 'discovered',
  }
  const merged = mergeNetworkCamera(list, rediscovered)
  assert.equal(merged.length, 1)
  assert.equal(merged[0].name, '田埂东侧')
  assert.equal(merged[0].vendor, '通用快照')
})

test('快照轮询地址带上时间戳，混合内容给出提示', () => {
  assert.equal(withCacheBuster('http://cam/snap.jpg', 7), 'http://cam/snap.jpg?_t=7')
  assert.equal(withCacheBuster('http://cam/snap.jpg?ch=1', 7), 'http://cam/snap.jpg?ch=1&_t=7')
  assert.equal(mixedContentWarning('http:', 'http://cam/snap.jpg'), null)
  assert.equal(mixedContentWarning('https:', 'https://cam/snap.jpg'), null)
  assert.match(mixedContentWarning('https:', 'http://cam/snap.jpg') ?? '', /HTTPS/)
})

test('每个站点的网络摄像头分别保存与读取', () => {
  const storage = memoryStorage()
  const camera = manualCamera('http://192.168.1.64/snapshot.jpg')
  saveNetworkCameras(storage, 'S02', [camera])
  saveNetworkCameras(storage, 'S03', [manualCamera('http://192.168.1.65/snapshot.jpg')])
  assert.deepEqual(loadNetworkCameras(storage, 'S02'), [camera])
  assert.equal(loadNetworkCameras(storage, 'S03').length, 1)
  assert.deepEqual(loadNetworkCameras(storage, 'S09'), [])
  saveNetworkCameras(storage, 'S02', [])
  assert.deepEqual(loadNetworkCameras(storage, 'S02'), [])
  assert.equal(loadNetworkCameras(storage, 'S03').length, 1)
})

test('损坏的本地存储不会影响列表读取', () => {
  assert.deepEqual(loadNetworkCameras(memoryStorage({ 'smart-rice-network-cameras': '{oops' }), 'S01'), [])
  assert.deepEqual(loadNetworkCameras(memoryStorage({ 'smart-rice-network-cameras': '{"S01":[{"id":"net:x"}]}' }), 'S01'), [])
  assert.deepEqual(loadNetworkCameras(null, 'S01'), [])
})

function probes(open: string[], images: Record<string, boolean>): ScanProbes & { imageCalls: string[] } {
  const imageCalls: string[] = []
  return {
    imageCalls,
    probePort: async (url) => open.some(origin => url === `${origin}/`),
    probeImage: async (url) => {
      imageCalls.push(url)
      return images[url] ?? false
    },
  }
}

test('扫描先探活再识别，命中首个匹配的快照路径', async () => {
  const probe = probes(
    ['http://192.168.1.64', 'http://192.168.1.70:8080'],
    { 'http://192.168.1.64/ISAPI/Streaming/channels/101/picture': true },
  )
  const progress: ScanProgress[] = []
  const found: NetworkCamera[] = []
  const result = await scanNetworkCameras({
    hosts: parseHostRange('192.168.1.64-70'),
    ports: [80, 8080],
    probes: probe,
    concurrency: 4,
    onProgress: (item) => progress.push(item),
    onCamera: (camera) => found.push(camera),
  })
  assert.deepEqual(result.cameras.map(camera => camera.id), found.map(camera => camera.id))
  assert.equal(result.cameras.length, 1)
  assert.equal(result.cameras[0].vendor, '海康 ISAPI')
  assert.equal(result.cameras[0].streamUrl, 'http://192.168.1.64/ISAPI/Streaming/channels/101/picture')
  // 端口开放但没有可识别快照的设备留给手动添加
  assert.deepEqual(result.unmatched, ['http://192.168.1.70:8080'])
  assert.equal(progress.at(0)?.total, 14)
  assert.equal(progress.at(-1)?.phase, 'idle')
  // 命中后不再继续尝试后面的厂商路径
  assert.equal(probe.imageCalls.filter(url => url.startsWith('http://192.168.1.64/')).length, 1)
})

test('识别到的摄像头带上厂商对应的取流与快照地址', () => {
  const camera = discoveredCamera('http://192.168.1.70:8080', {
    vendor: '大华 CGI', kind: 'mjpeg',
    stream: '/cgi-bin/mjpg/video.cgi?channel=1&subtype=1',
    snapshot: '/cgi-bin/snapshot.cgi',
  })
  assert.equal(camera.name, '192.168.1.70:8080 · 大华 CGI')
  assert.equal(camera.streamUrl, 'http://192.168.1.70:8080/cgi-bin/mjpg/video.cgi?channel=1&subtype=1')
  assert.equal(camera.snapshotUrl, 'http://192.168.1.70:8080/cgi-bin/snapshot.cgi')
})

test('停止扫描后不再探测剩余地址', async () => {
  const controller = new AbortController()
  let probed = 0
  const result = await scanNetworkCameras({
    hosts: parseHostRange('192.168.1.0/24'),
    ports: [80],
    concurrency: 2,
    signal: controller.signal,
    probes: {
      probePort: async () => {
        probed += 1
        if (probed === 4) controller.abort()
        return false
      },
      probeImage: async () => true,
    },
  })
  assert.ok(probed < 10, `探测应在停止后很快结束，实际 ${probed} 次`)
  assert.deepEqual(result.cameras, [])
})
