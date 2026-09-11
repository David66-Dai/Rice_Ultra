import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import type { DiagnosisRecord, DiagnosisTask, StationAlertStatus } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import {
  canCaptureMonitor,
  captureFileName,
  captureVideoFrame,
} from '../lib/camera-capture.ts'
import { emitDeviceStateChanged, linkageHint } from '../lib/devices.ts'
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
import './FieldInspection.css'

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

type CameraStatus = 'requesting' | 'live' | 'offline' | 'denied' | 'unavailable' | 'error'

const LIVE_STATION_ID = 'S01'
const LIVE_CAMERA_INDEX = 1
const IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp,image/bmp,.jpg,.jpeg,.png,.webp,.bmp'

function isLiveCamera(stationId: string, cameraIndex: number) {
  return stationId === LIVE_STATION_ID && cameraIndex === LIVE_CAMERA_INDEX
}

function stopStream(stream: MediaStream | null) {
  stream?.getTracks().forEach((track) => track.stop())
}

function cameraErrorText(error: unknown) {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      return '浏览器未授权摄像头，请允许后刷新页面'
    }
    if (error.name === 'NotFoundError' || error.name === 'OverconstrainedError') {
      return '未检测到可用摄像头'
    }
    if (error.name === 'NotReadableError') {
      return '摄像头被其他程序占用'
    }
  }
  return '摄像头启动失败，请检查设备连接'
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function formatClock(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}  ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function riceLeafReflectance(wavelengthNm: number) {
  if (wavelengthNm < 500) return 0.05 + 0.04 * ((wavelengthNm - 400) / 100)
  if (wavelengthNm < 580) return 0.09 + 0.13 * Math.sin(((wavelengthNm - 500) / 80) * Math.PI)
  if (wavelengthNm < 700) return 0.18 - 0.12 * ((wavelengthNm - 580) / 120)
  if (wavelengthNm < 760) return 0.06 + 0.46 * ((wavelengthNm - 700) / 60)
  return 0.5 + 0.05 * Math.sin(((wavelengthNm - 760) / 240) * Math.PI)
}

function buildHyperspectralReading(online: boolean) {
  const bandCount = 36
  const startNm = 400
  const endNm = 1000
  const points = Array.from({ length: bandCount }, (_, index) => {
    const wavelengthNm = startNm + (index / (bandCount - 1)) * (endNm - startNm)
    const reflectance = online ? riceLeafReflectance(wavelengthNm) : 0.08
    const x = (index / (bandCount - 1)) * 240
    const y = 36 - reflectance * 52
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  return {
    polyline: points.join(' '),
    label: online ? '未见光谱胁迫' : '暂无高光谱立方体',
    detail: online ? '128 波段 · 400–1000 nm · 红边正常 · 置信度 93.7%' : '等待站点重新上线',
    confidence: online ? 93.7 : 0,
    ariaLabel: online ? '高光谱反射率曲线，红边抬升正常' : '高光谱设备离线',
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
    return { badge: '上传识别', title: '点击上传叶片图片', detail: '细菌性叶枯病 / 褐斑病 / 东格鲁病毒将触发红色告警并开启喷药', percent: 0 }
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
    return { badge: '上传识别', title: '点击上传虫害图片', detail: '1 只黄色预警，2 只及以上红色告警并开启驱虫灯', percent: 0 }
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
  const [stationId, setStationId] = useState('S01')
  const [cameraIndex, setCameraIndex] = useState(1)
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [cameraStatus, setCameraStatus] = useState<CameraStatus>(() => (
    isLiveCamera('S01', 1) ? 'requesting' : 'offline'
  ))
  const [cameraMessage, setCameraMessage] = useState('正在连接摄像头…')
  const [videoSize, setVideoSize] = useState('— × —')
  const [restartKey, setRestartKey] = useState(0)
  const [now, setNow] = useState(() => new Date())
  const [uploading, setUploading] = useState<DiagnosisTask | null>(null)
  const [leafError, setLeafError] = useState<string | null>(null)
  const [pestError, setPestError] = useState<string | null>(null)
  const [leafHint, setLeafHint] = useState<string | null>(null)
  const [pestHint, setPestHint] = useState<string | null>(null)
  const [capture, setCapture] = useState<{ file: File, previewUrl: string } | null>(null)
  const [captureError, setCaptureError] = useState<string | null>(null)
  const [flashing, setFlashing] = useState(false)
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const devicesRef = useRef<MediaDeviceInfo[]>([])
  const leafInputRef = useRef<HTMLInputElement>(null)
  const pestInputRef = useRef<HTMLInputElement>(null)

  const station = useMemo(
    () => INSPECTION_STATIONS.find((item) => item.id === stationId) ?? INSPECTION_STATIONS[0],
    [stationId],
  )
  const stationOnline = canDiagnoseStation(station.id)
  const alert = byId[station.id]
  const stationLevel = stationVisualLevel(stationOnline, alert?.alertLevel)
  const liveFeed = isLiveCamera(stationId, cameraIndex)
  const selectedDevice = liveFeed && devices.length > 0 ? devices[0] : undefined
  const leafCopy = leafCardCopy(alert, uploading === 'leaf', leafError, stationOnline, leafHint)
  const pestCopy = pestCardCopy(alert, uploading === 'pest', pestError, stationOnline, pestHint)
  const hyperspectral = useMemo(() => buildHyperspectralReading(stationOnline), [stationOnline])
  const canCapture = canCaptureMonitor(stationOnline, cameraStatus === 'live', uploading !== null)

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    return () => {
      if (capture) URL.revokeObjectURL(capture.previewUrl)
    }
  }, [capture])

  useLiveCamera({
    stationId,
    cameraIndex,
    restartKey,
    videoRef,
    streamRef,
    devicesRef,
    setDevices,
    setCameraStatus,
    setCameraMessage,
    setVideoSize,
  })

  function handleVideoMeta() {
    const video = videoRef.current
    if (!video?.videoWidth) return
    setVideoSize(`${video.videoWidth} × ${video.videoHeight}`)
  }

  function closeCapture() {
    setCapture(null)
    setCaptureError(null)
  }

  function selectStation(id: string) {
    setStationId(id)
    setCameraIndex(1)
    setLeafError(null)
    setPestError(null)
    setLeafHint(null)
    setPestHint(null)
    closeCapture()
  }

  function retryCamera() {
    if (!isLiveCamera(stationId, cameraIndex)) return
    setCameraStatus('requesting')
    setCameraMessage('正在重新连接摄像头…')
    setRestartKey((value) => value + 1)
  }

  async function upload(task: DiagnosisTask, file: File) {
    if (!stationOnline) return false
    const form = new FormData()
    form.append('file', file)
    setUploading(task)
    setCaptureError(null)
    if (task === 'leaf') setLeafError(null)
    else setPestError(null)
    try {
      const record = await auth.request<DiagnosisRecord>(`/api/diagnosis/${task}?stationId=${encodeURIComponent(stationId)}`, {
        method: 'POST',
        body: form,
      })
      const hint = linkageHint(task, record.activatedDevice, record.deviceError)
      if (task === 'leaf') setLeafHint(hint)
      else setPestHint(hint)
      emitStationAlertsChanged()
      emitDeviceStateChanged()
      await refresh()
      return true
    } catch (error) {
      const message = describeError(error)
      if (task === 'leaf') setLeafError(message)
      else setPestError(message)
      setCaptureError(message)
      return false
    } finally {
      setUploading(null)
    }
  }

  async function takePhoto(replace = false) {
    const video = videoRef.current
    if (!video || !stationOnline || cameraStatus !== 'live' || uploading) return
    if (capture && !replace) return
    setCaptureError(null)
    setFlashing(true)
    window.setTimeout(() => setFlashing(false), 180)
    try {
      const file = await captureVideoFrame(video, captureFileName(stationId, cameraIndex))
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

  function onPick(task: DiagnosisTask, event: ChangeEvent<HTMLInputElement>) {
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
              autoPlay
              muted
              playsInline
              onLoadedMetadata={handleVideoMeta}
              aria-label={`${station.name}${cameraIndex}号摄像头实时画面`}
            />
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
              <span>{station.id}-CAM-{pad(cameraIndex)}</span>
              <span>{videoSize} / {cameraStatus === 'live' ? '实时' : '离线'}</span>
            </div>
            <div className="camera-screen__focus" aria-hidden="true"><i /></div>
            <div className="camera-screen__meta camera-screen__meta--bottom">
              <span>
                {station.name} · {liveFeed && selectedDevice?.label
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
                    {item.id} · {item.name}{item.id === LIVE_STATION_ID ? '（1号在线）' : '（未连接）'}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>选择摄像头</span>
              <select value={cameraIndex} onChange={(event) => {
                closeCapture()
                setCameraIndex(Number(event.target.value))
              }}>
                {Array.from({ length: station.cameraCount }, (_, index) => (
                  <option key={index + 1} value={index + 1}>
                    摄像头 {pad(index + 1)}{isLiveCamera(stationId, index + 1) ? '（在线）' : '（未连接）'}
                  </option>
                ))}
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
        </section>

        <section className="recognition-panel tech-panel" aria-labelledby="recognition-title">
          <header className="inspection-section-head">
            <div>
              <span>AI RECOGNITION MATRIX</span>
              <h2 id="recognition-title">智能识别分析</h2>
            </div>
            <small>{stationOnline ? '可拍照后选择叶害 / 虫害，或点击卡片上传图片' : '站点离线，识别已关闭'}</small>
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

            <article className={`recognition-card recognition-card--${stationLevel}`}>
              <div className="recognition-card__title">
                <div>
                  <small>HYPERSPECTRAL</small>
                  <h3>高光谱识别</h3>
                </div>
                <span>SVM</span>
              </div>
              <strong>{hyperspectral.label}</strong>
              <svg className="hyperspectral-curve" viewBox="0 0 240 40" role="img" aria-label={hyperspectral.ariaLabel}>
                <line x1="0" y1="20" x2="240" y2="20" />
                <polyline points={hyperspectral.polyline} />
              </svg>
              <div className="recognition-progress"><i style={{ width: `${hyperspectral.confidence}%` }} /></div>
              <p>{hyperspectral.detail}</p>
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
            <span><i className="is-attention" />黄色预警</span>
            <span><i className="is-danger" />红色告警</span>
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

function useLiveCamera(options: {
  stationId: string
  cameraIndex: number
  restartKey: number
  videoRef: { current: HTMLVideoElement | null }
  streamRef: { current: MediaStream | null }
  devicesRef: { current: MediaDeviceInfo[] }
  setDevices: (devices: MediaDeviceInfo[]) => void
  setCameraStatus: (status: CameraStatus) => void
  setCameraMessage: (message: string) => void
  setVideoSize: (size: string) => void
}) {
  const {
    stationId,
    cameraIndex,
    restartKey,
    videoRef,
    streamRef,
    devicesRef,
    setDevices,
    setCameraStatus,
    setCameraMessage,
    setVideoSize,
  } = options

  useEffect(() => {
    let cancelled = false

    function releaseCamera() {
      stopStream(streamRef.current)
      streamRef.current = null
      if (videoRef.current) videoRef.current.srcObject = null
    }

    async function startCamera() {
      if (!isLiveCamera(stationId, cameraIndex)) {
        releaseCamera()
        setCameraStatus('offline')
        setCameraMessage('该机位未接入，当前仅 1 号监测站 01 号摄像头在线')
        setVideoSize('— × —')
        return
      }

      if (!navigator.mediaDevices?.getUserMedia) {
        setCameraStatus('unavailable')
        setCameraMessage('当前浏览器不支持摄像头访问')
        return
      }

      setCameraStatus('requesting')
      setCameraMessage('正在连接摄像头…')
      releaseCamera()

      const target = devicesRef.current[0]

      try {
        const constraints: MediaStreamConstraints = {
          video: {
            ...(target ? { deviceId: { exact: target.deviceId } } : {}),
            width: { ideal: 1920 },
            height: { ideal: 1080 },
          },
          audio: false,
        }

        const stream = await navigator.mediaDevices.getUserMedia(constraints)
        if (cancelled) {
          stopStream(stream)
          return
        }

        streamRef.current = stream
        const video = videoRef.current
        if (video) {
          video.srcObject = stream
          video.muted = true
          await video.play().catch(() => undefined)
        }

        const listed = await navigator.mediaDevices.enumerateDevices()
        if (!cancelled) {
          const cameras = listed.filter((item) => item.kind === 'videoinput')
          devicesRef.current = cameras
          setDevices(cameras)
          setCameraStatus('live')
          setCameraMessage('传输正常')
        }
      } catch (error) {
        if (cancelled) return
        const denied = error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError')
        const missing = error instanceof DOMException && (error.name === 'NotFoundError' || error.name === 'OverconstrainedError')
        setCameraStatus(denied ? 'denied' : missing ? 'unavailable' : 'error')
        setCameraMessage(cameraErrorText(error))
        setVideoSize('— × —')
      }
    }

    void startCamera()

    return () => {
      cancelled = true
      releaseCamera()
    }
  }, [stationId, cameraIndex, restartKey, videoRef, streamRef, devicesRef, setDevices, setCameraStatus, setCameraMessage, setVideoSize])
}
