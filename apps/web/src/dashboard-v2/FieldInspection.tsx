import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import type { DiagnosisRecord, DiagnosisTask, LeafHsiDiagnosisResult, StationAlertStatus } from '@smart-rice-security/shared'
import { createCameraSession } from '../lib/inspection-camera'
import type { CameraStatus } from '../lib/inspection-camera'
import { useAuth } from '../auth/useAuth.ts'
import { ApiError, describeError } from '../lib/api.ts'
import {
  CrossOriginCaptureError,
  blobToCaptureFile,
  canCaptureMonitor,
  captureFileName,
  captureImageFrame,
  captureVideoFrame,
  snapshotProxyPath,
} from '../lib/camera-capture.ts'
import { emitDeviceStateChanged, linkageHint } from '../lib/devices.ts'
import {
  DEFAULT_SCAN_PORTS,
  createBrowserProbes,
  isNetworkCameraId,
  loadNetworkCameras,
  manualCamera,
  mergeNetworkCamera,
  mixedContentWarning,
  originLabel,
  parseHostRange,
  parsePorts,
  saveNetworkCameras,
  scanNetworkCameras,
  withCacheBuster,
} from '../lib/network-camera.ts'
import type { NetworkCamera, NetworkCameraKind, ScanProgress } from '../lib/network-camera.ts'
import {
  canDiagnoseStation,
  confidencePercent,
  emitStationAlertsChanged,
  formatConfidence,
  recognitionCardLevel,
  stationLevelLabel,
  stationVisualLevel,
} from '../lib/station-alerts.ts'
import { useStationAlerts } from './useStationAlerts.ts'
import { buildHyperspectralReading, type HyperspectralPrediction } from '../lib/hyperspectral.ts'
import './FieldInspection.css'

type InspectionUploadTask = DiagnosisTask | 'leaf-hsi'

type InspectionStation = {
  id: string
  name: string
  cameraCount: number
  temperature: number | null
  humidity: number | null
}

const INSPECTION_STATIONS: InspectionStation[] = [
  { id: 'S01', name: '1号监测站', cameraCount: 3, temperature: 26.8, humidity: 78 },
  { id: 'S02', name: '2号监测站', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S03', name: '3号监测站', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S04', name: '4号监测站', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S05', name: '5号监测站', cameraCount: 4, temperature: null, humidity: null },
  { id: 'S06', name: '6号监测站', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S07', name: '7号监测站', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S08', name: '8号监测站', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S09', name: '9号监测站', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S10', name: '10号监测站', cameraCount: 2, temperature: null, humidity: null },
]

const LIVE_STATION_ID = 'S01'
const IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp,image/bmp,.jpg,.jpeg,.png,.webp,.bmp'
const HSI_ACCEPT = '.h5,.hdf5,.zip,.npy,.npz,.hdr,application/x-hdf5,application/octet-stream'
const SNAPSHOT_INTERVAL_MS = 1000
const DEFAULT_SCAN_RANGE = '192.168.1.0/24'

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function formatClock(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}  ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function scanPhaseText(progress: ScanProgress) {
  if (progress.phase === 'reach') return '扫描网段'
  if (progress.phase === 'identify') return '识别摄像头'
  return '整理结果'
}

function hsiCardCopy(
  prediction: HyperspectralPrediction | null,
  uploading: boolean,
  error: string | null,
  online: boolean,
  hint: string | null,
) {
  if (!online) {
    return { badge: '离线', title: '站点离线', detail: '仅在线站点可上传立方体', percent: 0, action: '离线' }
  }
  if (uploading) {
    return { badge: '识别中', title: '立方体解析中…', detail: '正在提交高光谱 1D-CNN', percent: 42, action: '识别中' }
  }
  if (error) {
    return { badge: '失败', title: '识别失败', detail: error, percent: 0, action: '重试' }
  }
  if (prediction?.labelZh || prediction?.label) {
    const label = prediction.labelZh || prediction.label || '已识别'
    const confidence = prediction.confidence == null ? 0 : Math.round((prediction.confidence <= 1 ? prediction.confidence * 100 : prediction.confidence) * 10) / 10
    const detail = hint ? `点击光环可重新上传 · ${hint}` : '点击光环可重新上传立方体'
    return {
      badge: prediction.hasDamage ? '光谱预警' : '光谱正常',
      title: label,
      detail,
      percent: confidence,
      action: '再测',
    }
  }
  return {
    badge: 'CORE',
    title: '上传高光谱立方体',
    detail: hint ?? 'VIS–NIR · 400–1000 nm · 项目核心识别',
    percent: 0,
    action: '上传',
  }
}

function toHsiPrediction(record: DiagnosisRecord): HyperspectralPrediction {
  const result = record.result as LeafHsiDiagnosisResult
  return {
    label: result.label ?? record.label,
    labelZh: result.label_zh ?? record.labelZh,
    confidence: result.confidence ?? record.confidence,
    severity: result.severity,
    hasDamage: result.has_leaf_damage,
    spectrum: result.spectrum,
  }
}

function leafCardCopy(
  alert: StationAlertStatus | undefined,
  uploading: boolean,
  error: string | null,
  online: boolean,
  hint: string | null,
) {
  if (!online) {
    return { badge: '离线', title: '站点离线', detail: '仅在线站点可上传识别', percent: 0 }
  }
  if (uploading) {
    return { badge: '识别中', title: '正在识别叶片…', detail: '已提交到叶害模型', percent: 35 }
  }
  if (error) {
    return { badge: '失败', title: '识别失败', detail: error, percent: 0 }
  }
  if (!alert?.leafLabel && !alert?.leafLabelZh) {
    return { badge: '上传识别', title: '点击上传叶片图片', detail: '细菌性叶枯病 / 褐斑病 / 东格鲁病毒将触发红色告警并进入 AstrBot 消息确认', percent: 0 }
  }
  const label = alert.leafLabelZh ?? alert.leafLabel ?? '已识别'
  const confidence = formatConfidence(alert.leafConfidence)
  const detail = confidence ? `模型置信度 ${confidence} · 点击可重新上传` : '点击可重新上传'
  return {
    badge: alert.leafAlertLevel === 'red' ? '叶害告警' : '健康叶片',
    title: label,
    detail: hint ? `${detail} · ${hint}` : detail,
    percent: confidencePercent(alert.leafConfidence),
  }
}

function pestCardCopy(
  alert: StationAlertStatus | undefined,
  uploading: boolean,
  error: string | null,
  online: boolean,
  hint: string | null,
) {
  if (!online) {
    return { badge: '离线', title: '站点离线', detail: '仅在线站点可上传识别', percent: 0 }
  }
  if (uploading) {
    return { badge: '识别中', title: '正在识别虫害…', detail: '已提交到虫害模型', percent: 35 }
  }
  if (error) {
    return { badge: '失败', title: '识别失败', detail: error, percent: 0 }
  }
  if (alert?.pestCount == null) {
    return { badge: '上传识别', title: '点击上传虫害图片', detail: '1 只黄色预警，2 只及以上红色告警并进入 AstrBot 消息确认', percent: 0 }
  }
  const count = alert.pestCount
  const label = alert.pestLabel ? ` · ${alert.pestLabel}` : ''
  const badge = count >= 2 ? '虫害告警' : count === 1 ? '虫害预警' : '未见虫害'
  const detail = hint ? `点击可重新上传识别 · ${hint}` : '点击可重新上传识别'
  return {
    badge,
    title: count === 0 ? '未检出害虫' : `检出 ${count} 只${label}`,
    detail,
    percent: count === 0 ? 8 : Math.min(100, count * 40),
  }
}

export function FieldInspection() {
  const auth = useAuth()
  const { byId, refresh } = useStationAlerts()
  const [stationId, setStationId] = useState(LIVE_STATION_ID)
  const [offlineCameraIndex, setOfflineCameraIndex] = useState(1)
  const [selectedDeviceId, setSelectedDeviceId] = useState('')
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [connectionStatus, setCameraStatus] = useState<CameraStatus>('requesting')
  const [connectionMessage, setCameraMessage] = useState('正在连接摄像头…')
  const [videoSize, setVideoSize] = useState('— × —')
  const [now, setNow] = useState(() => new Date())
  const [uploading, setUploading] = useState<InspectionUploadTask | null>(null)
  const [leafError, setLeafError] = useState<string | null>(null)
  const [pestError, setPestError] = useState<string | null>(null)
  const [hsiError, setHsiError] = useState<string | null>(null)
  const [leafHint, setLeafHint] = useState<string | null>(null)
  const [pestHint, setPestHint] = useState<string | null>(null)
  const [hsiHint, setHsiHint] = useState<string | null>(null)
  const [hsiPrediction, setHsiPrediction] = useState<HyperspectralPrediction | null>(null)
  const [capture, setCapture] = useState<{ file: File, previewUrl: string } | null>(null)
  const [captureError, setCaptureError] = useState<string | null>(null)
  const [flashing, setFlashing] = useState(false)
  const [networkCameras, setNetworkCameras] = useState<NetworkCamera[]>(
    () => loadNetworkCameras(window.localStorage, LIVE_STATION_ID),
  )
  const [networkCameraId, setNetworkCameraId] = useState('')
  const [networkState, setNetworkState] = useState<{ id: string, status: CameraStatus, message: string }>(
    { id: '', status: 'requesting', message: '正在连接网络摄像头…' },
  )
  const [frameStamp, setFrameStamp] = useState(0)
  const [snapshotFallback, setSnapshotFallback] = useState(false)
  const [discoveryOpen, setDiscoveryOpen] = useState(false)
  const [scanRange, setScanRange] = useState(DEFAULT_SCAN_RANGE)
  const [scanPorts, setScanPorts] = useState(DEFAULT_SCAN_PORTS.join(','))
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<string | null>(null)
  const [scanSummary, setScanSummary] = useState<string | null>(null)
  const [scanProgress, setScanProgress] = useState<ScanProgress | null>(null)
  const [unmatchedHosts, setUnmatchedHosts] = useState<string[]>([])
  const [manualUrl, setManualUrl] = useState('')
  const [manualKind, setManualKind] = useState<NetworkCameraKind | 'auto'>('auto')
  const [manualError, setManualError] = useState<string | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const networkImageRef = useRef<HTMLImageElement>(null)
  const sessionRef = useRef<ReturnType<typeof createCameraSession> | null>(null)
  const leafInputRef = useRef<HTMLInputElement>(null)
  const pestInputRef = useRef<HTMLInputElement>(null)
  const hsiInputRef = useRef<HTMLInputElement>(null)
  const camerasRef = useRef(networkCameras)
  const preferredDeviceRef = useRef('')
  const scanRef = useRef<AbortController | null>(null)

  const station = useMemo(
    () => INSPECTION_STATIONS.find((item) => item.id === stationId) ?? INSPECTION_STATIONS[0],
    [stationId],
  )
  const stationOnline = canDiagnoseStation(station.id)
  const alert = byId[station.id]
  const stationLevel = stationVisualLevel(stationOnline, alert?.alertLevel)
  const liveFeed = stationId === LIVE_STATION_ID
  const cameraSupported = Boolean(navigator.mediaDevices?.getUserMedia)
  const networkCamera = networkCameras.find((item) => item.id === networkCameraId)
  const networkKind: NetworkCameraKind | null = networkCamera
    ? snapshotFallback ? 'snapshot' : networkCamera.kind
    : null
  // 画面地址与连接状态都由当前选中的摄像头推导，避免副作用里再写一遍状态。
  const networkFrame = !networkCamera
    ? ''
    : networkKind === 'mjpeg'
      ? frameStamp ? withCacheBuster(networkCamera.streamUrl, frameStamp) : networkCamera.streamUrl
      : withCacheBuster(networkCamera.snapshotUrl, frameStamp)
  const networkLinked = networkState.id === networkCameraId
  const cameraStatus: CameraStatus = networkCamera
    ? networkLinked ? networkState.status : 'requesting'
    : !liveFeed ? 'offline' : !cameraSupported ? 'unavailable' : connectionStatus
  const cameraMessage = networkCamera
    ? networkLinked ? networkState.message : '正在连接网络摄像头…'
    : !liveFeed
      ? '该站点未接入本机摄像头，可在下方“网络摄像头发现”中扫描并接入'
      : !cameraSupported ? '当前页面无法访问摄像头，请使用 HTTPS 或 localhost 并检查浏览器支持' : connectionMessage
  const networkIndex = networkCamera ? networkCameras.indexOf(networkCamera) + 1 : 0
  const cameraIndex = networkCamera
    ? networkIndex
    : liveFeed ? Math.max(1, devices.findIndex(device => device.deviceId === selectedDeviceId) + 1) : offlineCameraIndex
  const selectedDevice = liveFeed ? devices.find(device => device.deviceId === selectedDeviceId) : undefined
  const cameraSelectValue = networkCamera
    ? networkCamera.id
    : liveFeed ? selectedDeviceId : String(offlineCameraIndex)
  const leafCopy = leafCardCopy(alert, uploading === 'leaf', leafError, stationOnline, leafHint)
  const pestCopy = pestCardCopy(alert, uploading === 'pest', pestError, stationOnline, pestHint)
  const hsiCopy = hsiCardCopy(hsiPrediction, uploading === 'leaf-hsi', hsiError, stationOnline, hsiHint)
  const hyperspectral = buildHyperspectralReading(stationOnline, hsiPrediction)
  const hsiLevel = hsiPrediction?.hasDamage ? 'attention' : recognitionCardLevel(stationOnline, undefined)
  const canCapture = canCaptureMonitor(stationOnline, cameraStatus === 'live', uploading !== null)
  // HTTPS 页面会拦截所有 http:// 摄像头地址，扫描前先给出提示。
  const insecurePage = mixedContentWarning(window.location.protocol, 'http://')

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  // 选中网络摄像头时释放本机摄像头，切回本机时按上次选择重新接入。
  useEffect(() => {
    const video = videoRef.current
    if (!liveFeed || !cameraSupported || networkCameraId) return
    const session = createCameraSession(navigator.mediaDevices, (state) => {
      setDevices(state.devices)
      setSelectedDeviceId(state.selectedDeviceId)
      preferredDeviceRef.current = state.selectedDeviceId
      setCameraStatus(state.status)
      setCameraMessage(state.message)
      if (!state.stream) setVideoSize('— × —')
      if (video && video.srcObject !== state.stream) {
        video.srcObject = state.stream
        if (state.stream) void video.play().catch(() => undefined)
      }
    })
    sessionRef.current = session
    void session.select(preferredDeviceRef.current)
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void session.refresh()
    }
    document.addEventListener('visibilitychange', refreshWhenVisible)
    return () => {
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      session.dispose()
      sessionRef.current = null
      if (video) video.srcObject = null
    }
  }, [liveFeed, cameraSupported, networkCameraId])

  // MJPEG 交给 <img> 持续解码，只给快照的摄像头按秒刷新地址。
  useEffect(() => {
    if (!networkCamera || networkKind !== 'snapshot') return
    const timer = window.setInterval(() => setFrameStamp(Date.now()), SNAPSHOT_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [networkCamera, networkKind])

  useEffect(() => {
    return () => {
      if (capture) URL.revokeObjectURL(capture.previewUrl)
    }
  }, [capture])

  useEffect(() => () => scanRef.current?.abort(), [])

  function handleVideoMeta() {
    const video = videoRef.current
    if (!video?.videoWidth) return
    setVideoSize(`${video.videoWidth} × ${video.videoHeight}`)
  }

  function handleNetworkFrame() {
    const image = networkImageRef.current
    if (!image?.naturalWidth) return
    setVideoSize(`${image.naturalWidth} × ${image.naturalHeight}`)
    setNetworkState({ id: networkCameraId, status: 'live', message: '传输正常' })
  }

  function handleNetworkError() {
    if (!networkCamera) return
    // 有些设备只给快照不给 MJPEG，失败后自动降级为轮询。
    if (networkKind === 'mjpeg' && networkCamera.snapshotUrl !== networkCamera.streamUrl) {
      setSnapshotFallback(true)
      return
    }
    setVideoSize('— × —')
    setNetworkState({
      id: networkCameraId,
      status: 'error',
      message: mixedContentWarning(window.location.protocol, networkCamera.streamUrl)
        ?? '网络摄像头画面读取失败，请检查地址、登录凭据或设备是否在线',
    })
  }

  function closeCapture() {
    setCapture(null)
    setCaptureError(null)
  }

  function commitCameras(next: NetworkCamera[]) {
    camerasRef.current = next
    setNetworkCameras(next)
    saveNetworkCameras(window.localStorage, stationId, next)
  }

  function selectStation(id: string) {
    scanRef.current?.abort()
    scanRef.current = null
    const saved = loadNetworkCameras(window.localStorage, id)
    camerasRef.current = saved
    setNetworkCameras(saved)
    setNetworkCameraId('')
    setFrameStamp(0)
    setSnapshotFallback(false)
    setUnmatchedHosts([])
    setScanSummary(null)
    setScanError(null)
    setManualError(null)
    setStationId(id)
    setOfflineCameraIndex(1)
    if (id !== stationId) setVideoSize('— × —')
    setLeafError(null)
    setPestError(null)
    setLeafHint(null)
    setPestHint(null)
    closeCapture()
  }

  function chooseCamera(value: string) {
    closeCapture()
    setVideoSize('— × —')
    setSnapshotFallback(false)
    setFrameStamp(0)
    if (isNetworkCameraId(value)) {
      setNetworkCameraId(value)
      return
    }
    setNetworkCameraId('')
    if (!liveFeed) {
      setOfflineCameraIndex(Number(value))
      return
    }
    preferredDeviceRef.current = value
    void sessionRef.current?.select(value)
  }

  function removeCamera(id: string) {
    commitCameras(camerasRef.current.filter((item) => item.id !== id))
    if (networkCameraId === id) chooseCamera(liveFeed ? preferredDeviceRef.current : String(offlineCameraIndex))
  }

  function retryCamera() {
    if (networkCamera) {
      setSnapshotFallback(false)
      setNetworkState({ id: '', status: 'requesting', message: '正在连接网络摄像头…' })
      setFrameStamp(Date.now())
      return
    }
    void sessionRef.current?.retry()
  }

  async function startScan(event: FormEvent) {
    event.preventDefault()
    if (scanning) return
    if (insecurePage) {
      setScanError(insecurePage)
      return
    }
    let hosts: string[]
    let ports: number[]
    try {
      hosts = parseHostRange(scanRange)
      ports = parsePorts(scanPorts)
    } catch (error) {
      setScanError(error instanceof Error ? error.message : '扫描参数不正确')
      return
    }
    const controller = new AbortController()
    scanRef.current = controller
    setScanning(true)
    setScanError(null)
    setScanSummary(null)
    setUnmatchedHosts([])
    setScanProgress({ phase: 'reach', done: 0, total: hosts.length * ports.length })
    try {
      const result = await scanNetworkCameras({
        hosts,
        ports,
        probes: createBrowserProbes(),
        signal: controller.signal,
        onProgress: setScanProgress,
        onCamera: (camera) => {
          if (scanRef.current !== controller) return
          commitCameras(mergeNetworkCamera(camerasRef.current, camera))
        },
      })
      if (scanRef.current !== controller) return
      setUnmatchedHosts(result.unmatched)
      setScanSummary(controller.signal.aborted
        ? `扫描已停止，已发现 ${result.cameras.length} 台摄像头`
        : `扫描完成：发现 ${result.cameras.length} 台摄像头，另有 ${result.unmatched.length} 台设备开放 HTTP 端口`)
    } catch (error) {
      setScanError(error instanceof Error ? error.message : '扫描失败，请稍后重试')
    } finally {
      if (scanRef.current === controller) scanRef.current = null
      setScanning(false)
      setScanProgress(null)
    }
  }

  function addManualCamera(event: FormEvent) {
    event.preventDefault()
    try {
      const camera = manualCamera(manualUrl, manualKind === 'auto' ? undefined : manualKind)
      commitCameras(mergeNetworkCamera(camerasRef.current, camera))
      setManualUrl('')
      setManualError(mixedContentWarning(window.location.protocol, camera.streamUrl))
      chooseCamera(camera.id)
    } catch (error) {
      setManualError(error instanceof Error ? error.message : '地址不正确')
    }
  }

  async function upload(task: InspectionUploadTask, file: File) {
    if (!stationOnline) return false
    const form = new FormData()
    form.append('file', file)
    setUploading(task)
    setCaptureError(null)
    if (task === 'leaf') setLeafError(null)
    else if (task === 'pest') setPestError(null)
    else setHsiError(null)
    try {
      const record = await auth.request<DiagnosisRecord>(`/api/diagnosis/${task}?stationId=${encodeURIComponent(stationId)}`, {
        method: 'POST',
        body: form,
      })
      const hint = linkageHint(task === 'pest' ? 'pest' : 'leaf', record.activatedDevice, record.deviceError,
        record.confirmationRequired, record.pendingConfirmationId, record.alertDeliveryStatus)
      if (task === 'leaf') setLeafHint(hint)
      else if (task === 'pest') setPestHint(hint)
      else {
        setHsiHint(hint)
        setHsiPrediction(toHsiPrediction(record))
      }
      emitStationAlertsChanged()
      emitDeviceStateChanged()
      await refresh()
      return true
    } catch (error) {
      const message = describeError(error)
      if (task === 'leaf') setLeafError(message)
      else if (task === 'pest') setPestError(message)
      else setHsiError(message)
      if (task !== 'leaf-hsi') setCaptureError(message)
      return false
    } finally {
      setUploading(null)
    }
  }

  // 摄像头没开放跨域时画布会被污染，改由服务端代取一帧。
  async function captureNetworkFrame(camera: NetworkCamera, image: HTMLImageElement, filename: string) {
    try {
      return await captureImageFrame(image, filename)
    } catch (error) {
      if (!(error instanceof CrossOriginCaptureError)) throw error
      try {
        const blob = await auth.request<Blob>(snapshotProxyPath(camera.snapshotUrl), { responseType: 'blob' })
        return blobToCaptureFile(blob, filename)
      } catch (proxyError) {
        const reason = proxyError instanceof ApiError && proxyError.status === 404
          ? '后端还没有抓拍代理接口，请重新启动服务端后再试'
          : describeError(proxyError)
        throw new Error(`${error.message}，服务端代取也失败了：${reason}`)
      }
    }
  }

  async function takePhoto(replace = false) {
    const source = networkCamera ? networkImageRef.current : videoRef.current
    if (!source || !stationOnline || cameraStatus !== 'live' || uploading) return
    if (capture && !replace) return
    setCaptureError(null)
    setFlashing(true)
    window.setTimeout(() => setFlashing(false), 180)
    try {
      const file = networkCamera && source instanceof HTMLImageElement
        ? await captureNetworkFrame(networkCamera, source, captureFileName(stationId, cameraIndex, new Date(), 'NET'))
        : await captureVideoFrame(source as HTMLVideoElement, captureFileName(stationId, cameraIndex))
      setCapture({ file, previewUrl: URL.createObjectURL(file) })
    } catch (error) {
      setCaptureError(error instanceof Error ? error.message : '截取画面失败')
    }
  }

  async function submitCapture(task: DiagnosisTask) {
    if (!capture || uploading) return
    const ok = await upload(task, capture.file)
    if (ok) closeCapture()
  }

  function onPick(task: InspectionUploadTask, event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || !stationOnline || uploading) return
    void upload(task, file)
  }

  return (
    <div className="inspection-page">
      <div className="inspection-page__top">
        <section className="inspection-camera tech-panel" aria-labelledby="camera-title">
          <header className="inspection-section-head">
            <div>
              <span>FIELD CAMERA MONITOR</span>
              <h2 id="camera-title">站点摄像头画面</h2>
            </div>
            <div className={`camera-live${cameraStatus === 'live' ? '' : ' is-off'}`}>
              <i aria-hidden="true" />
              {cameraStatus === 'live' ? 'LIVE' : cameraStatus === 'requesting' ? 'LINKING' : 'OFFLINE'}
            </div>
          </header>

          <div className="camera-screen">
            <video
              ref={videoRef}
              className={networkCamera ? 'is-hidden' : undefined}
              autoPlay
              muted
              playsInline
              onLoadedMetadata={handleVideoMeta}
              aria-label={`${station.name}${cameraIndex}号摄像头实时画面`}
            />
            {networkCamera && networkFrame && (
              <img
                ref={networkImageRef}
                // 读取失败时只隐藏画面，元素保留下来才能在下一帧自行恢复。
                className={`camera-screen__network${cameraStatus === 'error' ? ' is-error' : ''}`}
                src={networkFrame}
                alt={`${station.name} 网络摄像头 ${networkCamera.name} 画面`}
                onLoad={handleNetworkFrame}
                onError={handleNetworkError}
              />
            )}
            {cameraStatus !== 'live' && (
              <div className="camera-screen__empty">
                <strong>
                  {cameraStatus === 'requesting' ? '正在接入真实摄像头' : cameraStatus === 'offline' ? '摄像头未连接' : '摄像头未就绪'}
                </strong>
                <p>{cameraMessage}</p>
                {cameraStatus !== 'requesting' && cameraStatus !== 'offline' && (
                  <button type="button" onClick={retryCamera}>重新连接</button>
                )}
              </div>
            )}
            <div className="camera-screen__tint" aria-hidden="true" />
            <span className="camera-screen__corner camera-screen__corner--tl" aria-hidden="true" />
            <span className="camera-screen__corner camera-screen__corner--tr" aria-hidden="true" />
            <span className="camera-screen__corner camera-screen__corner--bl" aria-hidden="true" />
            <span className="camera-screen__corner camera-screen__corner--br" aria-hidden="true" />

            <div className="camera-screen__meta camera-screen__meta--top">
              <span>{station.id}-{networkCamera ? 'NET' : 'CAM'}-{pad(cameraIndex)}</span>
              <span>{videoSize} / {cameraStatus === 'live' ? '实时' : '离线'}</span>
            </div>
            <div className="camera-screen__focus" aria-hidden="true"><i /></div>
            <div className="camera-screen__meta camera-screen__meta--bottom">
              <span>
                {station.name} · {networkCamera
                  ? `${networkCamera.name}${networkKind === 'mjpeg' ? ' · MJPEG' : ' · 快照'}`
                  : liveFeed && selectedDevice?.label
                    ? selectedDevice.label.replace(/\s*\([0-9a-fA-F:]{4,}\)\s*$/, '')
                    : `摄像头 ${pad(cameraIndex)}${liveFeed ? '' : ' · 未连接'}`}
              </span>
              <time dateTime={now.toISOString()}>{formatClock(now)}</time>
            </div>
            {flashing && <div className="camera-screen__flash" aria-hidden="true" />}
            {canCapture && !capture && (
              <button
                type="button"
                className="camera-shutter"
                onClick={() => void takePhoto()}
                aria-label="拍照"
              >
                <i />
                <span>拍照</span>
              </button>
            )}
            {capture && (
              <div className="camera-capture" role="dialog" aria-modal="true" aria-labelledby="capture-title">
                <img src={capture.previewUrl} alt="刚拍摄的监控画面" />
                <div className="camera-capture__panel">
                  <strong id="capture-title">选择识别类型</strong>
                  <p>确认画面后提交叶害或虫害识别</p>
                  {captureError && <em>{captureError}</em>}
                  <div className="camera-capture__actions">
                    <button
                      type="button"
                      className="is-leaf"
                      disabled={uploading !== null}
                      onClick={() => void submitCapture('leaf')}
                    >
                      {uploading === 'leaf' ? '叶害识别中…' : '叶害识别'}
                    </button>
                    <button
                      type="button"
                      className="is-pest"
                      disabled={uploading !== null}
                      onClick={() => void submitCapture('pest')}
                    >
                      {uploading === 'pest' ? '虫害识别中…' : '虫害识别'}
                    </button>
                    <button type="button" disabled={uploading !== null} onClick={() => void takePhoto(true)}>
                      重拍
                    </button>
                    <button type="button" disabled={uploading !== null} onClick={closeCapture}>
                      取消
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>

          <div className="camera-controls">
            <label>
              <span>选择监测站点</span>
              <select value={stationId} onChange={(event) => selectStation(event.target.value)}>
                {INSPECTION_STATIONS.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.id} · {item.name}
                    {item.id === LIVE_STATION_ID
                      ? `（已发现 ${devices.length} 台）`
                      : item.id === stationId && networkCameras.length
                        ? `（网络 ${networkCameras.length} 台）`
                        : '（未连接）'}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>选择摄像头 · 本机 {liveFeed ? devices.length : 0} 台 / 网络 {networkCameras.length} 台</span>
              <select
                value={cameraSelectValue}
                disabled={liveFeed && devices.length === 0 && networkCameras.length === 0}
                onChange={(event) => chooseCamera(event.target.value)}
              >
                <optgroup label="本机摄像头">
                  {liveFeed ? <>
                    {!selectedDevice && (
                      <option value={selectedDeviceId}>
                        {connectionStatus === 'requesting'
                          ? '正在检测摄像头…'
                          : devices.length ? '请选择可用摄像头' : '未检测到本机摄像头'}
                      </option>
                    )}
                    {devices.map((device, index) => (
                      <option key={device.deviceId} value={device.deviceId}>
                        摄像头 {pad(index + 1)} · {device.label || `视频设备 ${index + 1}`}
                      </option>
                    ))}
                  </> : Array.from({ length: station.cameraCount }, (_, index) => (
                    <option key={index + 1} value={index + 1}>摄像头 {pad(index + 1)}（未连接）</option>
                  ))}
                </optgroup>
                {networkCameras.length > 0 && (
                  <optgroup label="网络摄像头">
                    {networkCameras.map((item) => (
                      <option key={item.id} value={item.id}>{item.name}</option>
                    ))}
                  </optgroup>
                )}
              </select>
            </label>
            <div className="camera-controls__capture">
              <span>抓拍识别</span>
              <button
                type="button"
                disabled={!canCapture || capture !== null}
                onClick={() => void takePhoto()}
              >
                拍照
              </button>
            </div>
            <div className="camera-controls__status">
              <span>视频状态</span>
              <strong className={cameraStatus === 'live' ? 'is-ok' : 'is-off'}>
                <i />
                {cameraStatus === 'live' ? '传输正常' : cameraStatus === 'requesting' ? '连接中' : '未连接'}
              </strong>
            </div>
          </div>
          {captureError && !capture && (
            <p className="camera-capture-error" role="status">{captureError}</p>
          )}
          {liveFeed && <div className="camera-discovery">
            <span role="status">{cameraMessage} · 自动检测站点摄像头，插拔后更新列表</span>
            <button type="button" onClick={() => { void sessionRef.current?.refresh() }}>重新检测</button>
          </div>}

          <section className="camera-network" aria-labelledby="camera-network-title">
            <header className="camera-network__head">
              <div>
                <span>NETWORK CAMERA DISCOVERY</span>
                <strong id="camera-network-title">网络摄像头发现</strong>
              </div>
              <button
                type="button"
                aria-expanded={discoveryOpen}
                onClick={() => setDiscoveryOpen((open) => !open)}
              >
                {discoveryOpen ? '收起' : `展开 · 已接入 ${networkCameras.length} 台`}
              </button>
            </header>

            {discoveryOpen && (
              <div className="camera-network__body">
                <form className="camera-network__scan" onSubmit={(event) => void startScan(event)}>
                  <label>
                    <span>扫描网段</span>
                    <input
                      value={scanRange}
                      onChange={(event) => setScanRange(event.target.value)}
                      placeholder="192.168.1.0/24"
                      disabled={scanning}
                    />
                  </label>
                  <label>
                    <span>HTTP 端口</span>
                    <input
                      value={scanPorts}
                      onChange={(event) => setScanPorts(event.target.value)}
                      placeholder={DEFAULT_SCAN_PORTS.join(',')}
                      disabled={scanning}
                    />
                  </label>
                  <button type="submit" className="is-primary" disabled={scanning}>
                    {scanning ? '扫描中…' : '开始扫描'}
                  </button>
                  <button type="button" disabled={!scanning} onClick={() => scanRef.current?.abort()}>
                    停止
                  </button>
                </form>

                {scanProgress && (
                  <div className="camera-network__progress">
                    <i style={{ width: `${scanProgress.total ? (scanProgress.done / scanProgress.total) * 100 : 0}%` }} />
                    <span>{scanPhaseText(scanProgress)} {scanProgress.done}/{scanProgress.total}</span>
                  </div>
                )}
                {scanError && <p className="camera-network__error" role="alert">{scanError}</p>}
                {scanSummary && !scanError && <p className="camera-network__summary" role="status">{scanSummary}</p>}

                {networkCameras.length > 0 && (
                  <ul className="camera-network__list">
                    {networkCameras.map((item) => (
                      <li key={item.id} className={item.id === networkCameraId ? 'is-active' : undefined}>
                        <div>
                          <strong>{item.name}</strong>
                          <small>{item.vendor} · {item.kind === 'mjpeg' ? 'MJPEG 视频流' : '快照轮询'} · {item.streamUrl}</small>
                        </div>
                        <button type="button" onClick={() => chooseCamera(item.id)} disabled={item.id === networkCameraId}>
                          {item.id === networkCameraId ? '播放中' : '查看画面'}
                        </button>
                        <button type="button" className="is-ghost" onClick={() => removeCamera(item.id)}>移除</button>
                      </li>
                    ))}
                  </ul>
                )}

                {unmatchedHosts.length > 0 && (
                  <div className="camera-network__unmatched">
                    <span>开放 HTTP 但未识别（可能需要登录凭据），点击填入地址栏：</span>
                    <div>
                      {unmatchedHosts.map((origin) => (
                        <button key={origin} type="button" onClick={() => setManualUrl(`${origin}/`)}>
                          {originLabel(origin)}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                <form className="camera-network__manual" onSubmit={addManualCamera}>
                  <label>
                    <span>手动添加地址</span>
                    <input
                      value={manualUrl}
                      onChange={(event) => setManualUrl(event.target.value)}
                      placeholder="http://192.168.1.64/snapshot.jpg"
                    />
                  </label>
                  <label>
                    <span>画面类型</span>
                    <select value={manualKind} onChange={(event) => setManualKind(event.target.value as NetworkCameraKind | 'auto')}>
                      <option value="auto">自动判断</option>
                      <option value="mjpeg">MJPEG 视频流</option>
                      <option value="snapshot">快照轮询</option>
                    </select>
                  </label>
                  <button type="submit" className="is-primary">添加</button>
                </form>
                {manualError && <p className="camera-network__error" role="alert">{manualError}</p>}

                <p className="camera-network__tip">
                  {insecurePage ?? '扫描在浏览器内发起，仅能发现同一局域网内开放 HTTP 的摄像头；RTSP 需由设备提供 HTTP 快照或 MJPEG 地址。未开放跨域的摄像头照样能抓拍，会自动改由服务器代取一帧（需服务器可访问该网段）；需要登录的摄像头可写成 http://用户名:密码@地址/路径。'}
                </p>
              </div>
            )}
          </section>
        </section>

        <section className="recognition-panel tech-panel" aria-labelledby="recognition-title">
          <header className="inspection-section-head">
            <div>
              <span>AI RECOGNITION MATRIX</span>
              <h2 id="recognition-title">智能识别分析</h2>
            </div>
            <small>{stationOnline ? '可拍照后选择叶害 / 虫害，或点击高光谱光环上传立方体' : '站点离线，识别已关闭'}</small>
          </header>

          <input
            ref={leafInputRef}
            type="file"
            accept={IMAGE_ACCEPT}
            hidden
            onChange={(event) => onPick('leaf', event)}
          />
          <input
            ref={pestInputRef}
            type="file"
            accept={IMAGE_ACCEPT}
            hidden
            onChange={(event) => onPick('pest', event)}
          />
          <input
            ref={hsiInputRef}
            type="file"
            accept={HSI_ACCEPT}
            hidden
            onChange={(event) => onPick('leaf-hsi', event)}
          />

          <div className="recognition-list">

            <button
              type="button"
              className={`recognition-card${stationOnline ? ' recognition-card--upload' : ''} recognition-card--${recognitionCardLevel(stationOnline, alert?.leafAlertLevel)}${uploading === 'leaf' ? ' is-busy' : ''}`}
              onClick={() => stationOnline && leafInputRef.current?.click()}
              disabled={!stationOnline || uploading !== null}
            >
              <div className="recognition-card__title">
                <div>
                  <small>LEAF DAMAGE</small>
                  <h3>叶害识别</h3>
                </div>
                <span>{leafCopy.badge}</span>
              </div>
              <strong>{leafCopy.title}</strong>
              <div className="recognition-progress"><i style={{ width: `${leafCopy.percent}%` }} /></div>
              <p>{leafCopy.detail}</p>
            </button>

            <button
              type="button"
              className={`recognition-card${stationOnline ? ' recognition-card--upload' : ''} recognition-card--${recognitionCardLevel(stationOnline, alert?.pestAlertLevel)}${uploading === 'pest' ? ' is-busy' : ''}`}
              onClick={() => stationOnline && pestInputRef.current?.click()}
              disabled={!stationOnline || uploading !== null}
            >
              <div className="recognition-card__title">
                <div>
                  <small>PEST DETECTION</small>
                  <h3>虫害识别</h3>
                </div>
                <span>{pestCopy.badge}</span>
              </div>
              <strong>{pestCopy.title}</strong>
              <div className="recognition-progress"><i style={{ width: `${pestCopy.percent}%` }} /></div>
              <p>{pestCopy.detail}</p>
            </button>

            <article className={`recognition-card recognition-card--hsi-core recognition-card--${hsiLevel}${uploading === 'leaf-hsi' ? ' is-busy' : ''}${stationOnline ? ' recognition-card--upload' : ''}`}>
              <div className="hsi-core__copy">
                <div className="recognition-card__title">
                  <div>
                    <small>CORE · HYPERSPECTRAL</small>
                    <h3>高光谱识别</h3>
                  </div>
                  <span>{hsiCopy.badge}</span>
                </div>
                <strong>{hyperspectral.label}</strong>
                <svg className="hyperspectral-curve" viewBox="0 0 240 40" role="img" aria-label={hyperspectral.ariaLabel}>
                  <line x1="0" y1="20" x2="240" y2="20" />
                  <polyline points={hyperspectral.polyline} />
                </svg>
                <div className="recognition-progress"><i style={{ width: `${hyperspectral.confidence}%` }} /></div>
                <p>{hsiError ?? hyperspectral.detail}</p>
              </div>
              <button
                type="button"
                className={`hsi-upload-orb${uploading === 'leaf-hsi' ? ' is-busy' : ''}${hsiPrediction?.hasDamage ? ' is-warn' : hsiPrediction ? ' is-ready' : ''}`}
                onClick={() => stationOnline && hsiInputRef.current?.click()}
                disabled={!stationOnline || uploading !== null}
                aria-label={stationOnline ? `高光谱${hsiCopy.action}` : '高光谱识别离线'}
              >
                <span className="hsi-upload-orb__halo" aria-hidden="true" />
                <span className="hsi-upload-orb__wave" aria-hidden="true" />
                <span className="hsi-upload-orb__wave hsi-upload-orb__wave--late" aria-hidden="true" />
                <span className="hsi-upload-orb__spark" aria-hidden="true" />
                <span className="hsi-upload-orb__ring" aria-hidden="true" />
                <span className="hsi-upload-orb__core">
                  <small>VIS–NIR</small>
                  <strong>{hsiCopy.action}</strong>
                </span>
              </button>
            </article>
          </div>
        </section>
      </div>

      <section className="station-status tech-panel" aria-labelledby="station-status-title">
        <header className="inspection-section-head">
          <div>
            <span>STATION HEALTH STATUS</span>
            <h2 id="station-status-title">当前站点状态</h2>
          </div>
          <div className="station-status__legend">
            <span><i className="is-normal" />正常</span>
            <span><i className="is-attention" />预警</span>
            <span><i className="is-danger" />严重</span>
            <span><i className="is-offline" />离线</span>
          </div>
        </header>

        <div className="station-status__grid">
          {INSPECTION_STATIONS.map((item) => {
            const itemOnline = item.id === LIVE_STATION_ID
            const itemAlert = byId[item.id]
            const level = stationVisualLevel(itemOnline, itemAlert?.alertLevel)
            return (
              <button
                key={item.id}
                type="button"
                className={`station-status-card station-status-card--${level}${stationId === item.id ? ' is-selected' : ''}`}
                onClick={() => selectStation(item.id)}
              >
                <span className="station-status-card__light" aria-hidden="true"><i /></span>
                <div>
                  <small>{item.id}</small>
                  <strong>{item.name}</strong>
                </div>
                <dl>
                  <div><dt>温度</dt><dd>{item.temperature == null ? '--' : `${item.temperature}℃`}</dd></div>
                  <div><dt>湿度</dt><dd>{item.humidity == null ? '--' : `${item.humidity}%`}</dd></div>
                </dl>
                <em>{stationLevelLabel(itemOnline, itemAlert?.alertLevel)}</em>
              </button>
            )
          })}
        </div>
      </section>
    </div>
  )
}
