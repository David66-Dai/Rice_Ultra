export type NetworkCameraKind = 'mjpeg' | 'snapshot'

export type NetworkCamera = {
  id: string
  name: string
  origin: string
  streamUrl: string
  snapshotUrl: string
  kind: NetworkCameraKind
  vendor: string
  source: 'discovered' | 'manual'
}

export type CameraProfile = {
  vendor: string
  kind: NetworkCameraKind
  stream: string
  snapshot: string
}

export type ScanPhase = 'idle' | 'reach' | 'identify'

export type ScanProgress = { phase: ScanPhase, done: number, total: number }

// Probes are injected so the scanner stays testable outside the browser.
export type ScanProbes = {
  probePort: (url: string, signal?: AbortSignal) => Promise<boolean>
  probeImage: (url: string, signal?: AbortSignal) => Promise<boolean>
}

export type ScanOptions = {
  hosts: string[]
  ports: number[]
  probes: ScanProbes
  concurrency?: number
  signal?: AbortSignal
  onProgress?: (progress: ScanProgress) => void
  onCamera?: (camera: NetworkCamera) => void
}

export type ScanResult = {
  cameras: NetworkCamera[]
  /** HTTP 端口开放但未匹配到快照路径的设备，通常需要手动填写地址或凭据。 */
  unmatched: string[]
}

export const DEFAULT_SCAN_PORTS = [80, 8080, 8000, 81]
export const NETWORK_CAMERA_STORAGE_KEY = 'smart-rice-network-cameras'
export const NETWORK_CAMERA_ID_PREFIX = 'net:'

// Snapshot paths identify the vendor with a plain <img> probe; the stream path is
// what the monitor plays afterwards.
export const CAMERA_PROFILES: CameraProfile[] = [
  { vendor: '海康 ISAPI', kind: 'snapshot', stream: '/ISAPI/Streaming/channels/101/picture', snapshot: '/ISAPI/Streaming/channels/101/picture' },
  { vendor: '大华 CGI', kind: 'mjpeg', stream: '/cgi-bin/mjpg/video.cgi?channel=1&subtype=1', snapshot: '/cgi-bin/snapshot.cgi' },
  { vendor: 'Axis VAPIX', kind: 'mjpeg', stream: '/axis-cgi/mjpg/video.cgi', snapshot: '/axis-cgi/jpg/image.cgi' },
  { vendor: 'ONVIF 快照', kind: 'snapshot', stream: '/onvif-http/snapshot?Profile_1', snapshot: '/onvif-http/snapshot?Profile_1' },
  { vendor: '通用 MJPEG', kind: 'mjpeg', stream: '/mjpg/video.mjpg', snapshot: '/snapshot.jpg' },
  { vendor: 'Foscam CGI', kind: 'mjpeg', stream: '/videostream.cgi', snapshot: '/snapshot.cgi' },
  { vendor: 'ESP32-CAM', kind: 'snapshot', stream: '/capture', snapshot: '/capture' },
  { vendor: 'MotionEye', kind: 'snapshot', stream: '/picture/1/current/', snapshot: '/picture/1/current/' },
  { vendor: '通用快照', kind: 'snapshot', stream: '/tmpfs/auto.jpg', snapshot: '/tmpfs/auto.jpg' },
]

function octet(value: string): number {
  if (!/^\d{1,3}$/.test(value)) throw new Error(`网段格式不正确：${value}`)
  const parsed = Number(value)
  if (parsed > 255) throw new Error(`网段格式不正确：${value}`)
  return parsed
}

function numbersInRange(from: number, to: number): number[] {
  const start = Math.min(from, to)
  const end = Math.max(from, to)
  return Array.from({ length: end - start + 1 }, (_, index) => start + index)
}

function hostsInRange(prefix: string, from: number, to: number): string[] {
  return numbersInRange(from, to).map(value => `${prefix}.${value}`)
}

function expandHostPart(part: string): string[] {
  const cidr = /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.(\d{1,3})\/(\d{1,2})$/.exec(part)
  if (cidr) {
    const [, prefix, last, bits] = cidr
    prefix.split('.').forEach(octet)
    const size = Number(bits)
    if (size < 24 || size > 32) throw new Error('仅支持 /24 至 /32 的网段，例如 192.168.1.0/24')
    const block = 2 ** (32 - size)
    const base = octet(last) - (octet(last) % block)
    const edge = block > 2 ? 1 : 0
    return hostsInRange(prefix, base + edge, base + block - 1 - edge)
  }
  const range = /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.(\d{1,3})-(\d{1,3})$/.exec(part)
  if (range) {
    const [, prefix, from, to] = range
    prefix.split('.').forEach(octet)
    return hostsInRange(prefix, octet(from), octet(to))
  }
  const single = /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.(\d{1,3})$/.exec(part)
  if (single) {
    const [, prefix, last] = single
    prefix.split('.').forEach(octet)
    return [`${prefix}.${octet(last)}`]
  }
  const base = /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.?$/.exec(part)
  if (base) {
    base[1].split('.').forEach(octet)
    return hostsInRange(base[1], 1, 254)
  }
  if (/^[a-z0-9][a-z0-9.-]*$/i.test(part)) return [part.toLowerCase()]
  throw new Error(`无法识别的地址：${part}`)
}

export function parseHostRange(input: string, limit = 512): string[] {
  const parts = input.split(/[,\s]+/).filter(Boolean)
  if (!parts.length) throw new Error('请填写要扫描的网段，例如 192.168.1.0/24')
  const hosts = new Set<string>()
  for (const part of parts) {
    for (const host of expandHostPart(part)) hosts.add(host)
  }
  if (hosts.size > limit) throw new Error(`扫描范围过大（${hosts.size} 个地址），请缩小到 ${limit} 个以内`)
  return [...hosts]
}

export function parsePorts(input: string, limit = 8): number[] {
  const parts = input.split(/[,\s]+/).filter(Boolean)
  if (!parts.length) return [...DEFAULT_SCAN_PORTS]
  const ports = new Set<number>()
  for (const part of parts) {
    const range = /^(\d{1,5})-(\d{1,5})$/.exec(part)
    const values = range ? numbersInRange(Number(range[1]), Number(range[2])) : [Number(part)]
    for (const value of values) {
      if (!Number.isInteger(value) || value < 1 || value > 65535) throw new Error(`端口不正确：${part}`)
      ports.add(value)
    }
  }
  if (ports.size > limit) throw new Error(`端口过多（${ports.size} 个），请保留 ${limit} 个以内`)
  return [...ports]
}

export function buildScanOrigins(hosts: string[], ports: number[]): string[] {
  const origins: string[] = []
  for (const host of hosts) {
    for (const port of ports) origins.push(port === 80 ? `http://${host}` : `http://${host}:${port}`)
  }
  return origins
}

function originParts(origin: string) {
  const url = new URL(origin)
  const numbers = url.hostname.split('.').map(value => Number(value))
  const key = numbers.length === 4 && numbers.every(value => Number.isInteger(value))
    ? numbers.reduce((total, value) => total * 256 + value, 0)
    : Number.MAX_SAFE_INTEGER
  return { key, hostname: url.hostname, port: Number(url.port || 80) }
}

export function compareOrigins(left: string, right: string): number {
  const a = originParts(left)
  const b = originParts(right)
  if (a.key !== b.key) return a.key - b.key
  if (a.hostname !== b.hostname) return a.hostname < b.hostname ? -1 : 1
  return a.port - b.port
}

export function originLabel(origin: string): string {
  const url = new URL(origin)
  return url.port ? `${url.hostname}:${url.port}` : url.hostname
}

export function inferCameraKind(url: string): NetworkCameraKind {
  const path = url.toLowerCase()
  if (/\.(jpe?g|png|bmp)(\?|$)/.test(path)) return 'snapshot'
  if (/(snapshot|picture|capture|image|still)/.test(path)) return 'snapshot'
  if (/(mjpe?g|stream|video|live|channel)/.test(path)) return 'mjpeg'
  return 'snapshot'
}

export function networkCameraId(streamUrl: string): string {
  return `${NETWORK_CAMERA_ID_PREFIX}${streamUrl}`
}

export function isNetworkCameraId(value: string): boolean {
  return value.startsWith(NETWORK_CAMERA_ID_PREFIX)
}

export function discoveredCamera(origin: string, profile: CameraProfile): NetworkCamera {
  const streamUrl = `${origin}${profile.stream}`
  return {
    id: networkCameraId(streamUrl),
    name: `${originLabel(origin)} · ${profile.vendor}`,
    origin,
    streamUrl,
    snapshotUrl: `${origin}${profile.snapshot}`,
    kind: profile.kind,
    vendor: profile.vendor,
    source: 'discovered',
  }
}

export function manualCamera(input: string, kind?: NetworkCameraKind, name?: string): NetworkCamera {
  const raw = input.trim()
  if (!raw) throw new Error('请填写网络摄像头地址')
  if (/^rtsp:|^rtmp:/i.test(raw)) {
    throw new Error('浏览器无法直接播放 RTSP/RTMP，请填写摄像头的 HTTP 快照或 MJPEG 地址')
  }
  let url: URL
  try {
    url = new URL(/^https?:\/\//i.test(raw) ? raw : `http://${raw}`)
  } catch {
    throw new Error('地址格式不正确，例如 http://192.168.1.64/snapshot.jpg')
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('仅支持 http/https 地址')
  const streamUrl = url.toString()
  return {
    id: networkCameraId(streamUrl),
    name: name?.trim() || `${originLabel(url.origin)} · 手动添加`,
    origin: url.origin,
    streamUrl,
    snapshotUrl: streamUrl,
    kind: kind ?? inferCameraKind(streamUrl),
    vendor: '手动添加',
    source: 'manual',
  }
}

export function mergeNetworkCamera(list: NetworkCamera[], camera: NetworkCamera, limit = 24): NetworkCamera[] {
  const index = list.findIndex(item => item.id === camera.id)
  // A rescan refreshes an existing entry in place; a manually named camera keeps its name.
  if (index >= 0) {
    const next = [...list]
    const current = list[index]
    next[index] = camera.source === 'discovered' && current.source === 'manual'
      ? { ...camera, name: current.name, source: 'manual' }
      : camera
    return next
  }
  return [...list, camera].slice(-limit)
}

export function withCacheBuster(url: string, stamp: number): string {
  return `${url}${url.includes('?') ? '&' : '?'}_t=${stamp}`
}

export function mixedContentWarning(pageProtocol: string, target: string): string | null {
  if (pageProtocol !== 'https:' || !/^http:/i.test(target)) return null
  return '当前页面为 HTTPS，浏览器会拦截 http:// 摄像头地址，请改用 http 访问大屏或为摄像头启用 HTTPS'
}

function isNetworkCamera(value: unknown): value is NetworkCamera {
  const camera = value as NetworkCamera | null
  return Boolean(camera && typeof camera.id === 'string' && typeof camera.streamUrl === 'string'
    && typeof camera.snapshotUrl === 'string' && (camera.kind === 'mjpeg' || camera.kind === 'snapshot'))
}

type CameraStorage = Pick<Storage, 'getItem' | 'setItem'>

export function loadNetworkCameras(storage: CameraStorage | null | undefined, stationId: string): NetworkCamera[] {
  try {
    const raw = storage?.getItem(NETWORK_CAMERA_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const list = parsed?.[stationId]
    return Array.isArray(list) ? list.filter(isNetworkCamera) : []
  } catch {
    return []
  }
}

export function saveNetworkCameras(storage: CameraStorage | null | undefined, stationId: string, cameras: NetworkCamera[]) {
  if (!storage) return
  try {
    const raw = storage.getItem(NETWORK_CAMERA_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as Record<string, unknown> : {}
    const next = typeof parsed === 'object' && parsed !== null ? parsed : {}
    if (cameras.length) next[stationId] = cameras
    else delete next[stationId]
    storage.setItem(NETWORK_CAMERA_STORAGE_KEY, JSON.stringify(next))
  } catch {
    // 本地存储不可用时只影响持久化，不影响本次会话继续使用。
  }
}

async function runPool<T>(items: T[], limit: number, task: (item: T) => Promise<void>, signal?: AbortSignal) {
  let cursor = 0
  const workers = Math.max(1, Math.min(limit, items.length))
  await Promise.all(Array.from({ length: workers }, async () => {
    while (cursor < items.length) {
      if (signal?.aborted) return
      const item = items[cursor]
      cursor += 1
      await task(item)
    }
  }))
}

// Two passes: an open HTTP port narrows the subnet down, then snapshot paths name the vendor.
export async function scanNetworkCameras(options: ScanOptions): Promise<ScanResult> {
  const { hosts, ports, probes, signal, onProgress, onCamera } = options
  const concurrency = Math.max(1, options.concurrency ?? 24)
  const origins = buildScanOrigins(hosts, ports)
  const reachable: string[] = []
  let reached = 0
  onProgress?.({ phase: 'reach', done: 0, total: origins.length })
  await runPool(origins, concurrency, async (origin) => {
    const open = await probes.probePort(`${origin}/`, signal)
    reached += 1
    if (open) reachable.push(origin)
    onProgress?.({ phase: 'reach', done: reached, total: origins.length })
  }, signal)

  const targets = reachable.sort(compareOrigins)
  if (signal?.aborted) {
    onProgress?.({ phase: 'idle', done: reached, total: origins.length })
    return { cameras: [], unmatched: targets }
  }

  const cameras: NetworkCamera[] = []
  const identified = new Set<string>()
  let checked = 0
  onProgress?.({ phase: 'identify', done: 0, total: targets.length })
  await runPool(targets, Math.min(concurrency, 8), async (origin) => {
    for (const profile of CAMERA_PROFILES) {
      if (signal?.aborted) break
      if (!await probes.probeImage(`${origin}${profile.snapshot}`, signal)) continue
      const camera = discoveredCamera(origin, profile)
      identified.add(origin)
      cameras.push(camera)
      onCamera?.(camera)
      break
    }
    checked += 1
    onProgress?.({ phase: 'identify', done: checked, total: targets.length })
  }, signal)

  onProgress?.({ phase: 'idle', done: checked, total: targets.length })
  return { cameras, unmatched: targets.filter(origin => !identified.has(origin)) }
}

// Browser probes: an opaque no-cors response proves the port answers HTTP (401 included),
// and an <img> that decodes proves the path really is a snapshot.
export function createBrowserProbes(timeouts: { port?: number, image?: number } = {}): ScanProbes {
  const portTimeout = timeouts.port ?? 1200
  const imageTimeout = timeouts.image ?? 2500
  return {
    async probePort(url, signal) {
      if (signal?.aborted) return false
      const controller = new AbortController()
      const abort = () => controller.abort()
      signal?.addEventListener('abort', abort)
      const timer = window.setTimeout(abort, portTimeout)
      try {
        await fetch(url, { mode: 'no-cors', cache: 'no-store', signal: controller.signal })
        return true
      } catch {
        return false
      } finally {
        window.clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
      }
    },
    probeImage(url, signal) {
      if (signal?.aborted) return Promise.resolve(false)
      return new Promise<boolean>((resolve) => {
        const image = new Image()
        let timer = 0
        const finish = (found: boolean) => {
          window.clearTimeout(timer)
          signal?.removeEventListener('abort', cancel)
          image.onload = null
          image.onerror = null
          image.src = ''
          resolve(found)
        }
        const cancel = () => finish(false)
        image.onload = () => finish(image.naturalWidth > 0)
        image.onerror = () => finish(false)
        signal?.addEventListener('abort', cancel)
        timer = window.setTimeout(cancel, imageTimeout)
        image.src = withCacheBuster(url, Date.now())
      })
    },
  }
}
