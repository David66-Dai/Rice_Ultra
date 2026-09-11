import { useEffect, useMemo, useRef, useState } from 'react'
import { createCameraSession } from '../lib/inspection-camera'
import type { CameraStatus } from '../lib/inspection-camera'
import './FieldInspection.css'

type StationLevel = 'normal' | 'offline'

type InspectionStation = {
  id: string
  name: string
  level: StationLevel
  levelLabel: string
  cameraCount: number
  temperature: number | null
  humidity: number | null
}

const INSPECTION_STATIONS: InspectionStation[] = [
  { id: 'S01', name: '1号监测站', level: 'normal', levelLabel: '在线', cameraCount: 3, temperature: 26.8, humidity: 78 },
  { id: 'S02', name: '2号监测站', level: 'offline', levelLabel: '离线', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S03', name: '3号监测站', level: 'offline', levelLabel: '离线', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S04', name: '4号监测站', level: 'offline', levelLabel: '离线', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S05', name: '5号监测站', level: 'offline', levelLabel: '离线', cameraCount: 4, temperature: null, humidity: null },
  { id: 'S06', name: '6号监测站', level: 'offline', levelLabel: '离线', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S07', name: '7号监测站', level: 'offline', levelLabel: '离线', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S08', name: '8号监测站', level: 'offline', levelLabel: '离线', cameraCount: 2, temperature: null, humidity: null },
  { id: 'S09', name: '9号监测站', level: 'offline', levelLabel: '离线', cameraCount: 3, temperature: null, humidity: null },
  { id: 'S10', name: '10号监测站', level: 'offline', levelLabel: '离线', cameraCount: 2, temperature: null, humidity: null },
]

const LIVE_STATION_ID = 'S01'

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function formatClock(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}  ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function FieldInspection() {
  const [stationId, setStationId] = useState('S01')
  const [offlineCameraIndex, setOfflineCameraIndex] = useState(1)
  const [selectedDeviceId, setSelectedDeviceId] = useState('')
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [connectionStatus, setCameraStatus] = useState<CameraStatus>('requesting')
  const [connectionMessage, setCameraMessage] = useState('正在连接摄像头…')
  const [videoSize, setVideoSize] = useState('— × —')
  const [now, setNow] = useState(() => new Date())
  const videoRef = useRef<HTMLVideoElement>(null)
  const sessionRef = useRef<ReturnType<typeof createCameraSession> | null>(null)

  const station = useMemo(
    () => INSPECTION_STATIONS.find((item) => item.id === stationId) ?? INSPECTION_STATIONS[0],
    [stationId],
  )

  const liveFeed = stationId === LIVE_STATION_ID
  const cameraSupported = Boolean(navigator.mediaDevices?.getUserMedia)
  const cameraStatus = !liveFeed ? 'offline' : !cameraSupported ? 'unavailable' : connectionStatus
  const cameraMessage = !liveFeed
    ? '该站点尚未接入摄像头，本机摄像头请在 1 号监测站选择'
    : !cameraSupported ? '当前页面无法访问摄像头，请使用 HTTPS 或 localhost 并检查浏览器支持' : connectionMessage
  const cameraIndex = liveFeed ? Math.max(1, devices.findIndex(device => device.deviceId === selectedDeviceId) + 1) : offlineCameraIndex
  const selectedDevice = liveFeed ? devices.find(device => device.deviceId === selectedDeviceId) : undefined

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    const video = videoRef.current
    if (!liveFeed || !cameraSupported) return
    const session = createCameraSession(navigator.mediaDevices, (state) => {
      setDevices(state.devices)
      setSelectedDeviceId(state.selectedDeviceId)
      setCameraStatus(state.status)
      setCameraMessage(state.message)
      if (!state.stream) setVideoSize('— × —')
      if (video && video.srcObject !== state.stream) {
        video.srcObject = state.stream
        if (state.stream) void video.play().catch(() => undefined)
      }
    })
    sessionRef.current = session
    void session.start()
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
  }, [liveFeed, cameraSupported])

  function handleVideoMeta() {
    const video = videoRef.current
    if (!video?.videoWidth) return
    setVideoSize(`${video.videoWidth} × ${video.videoHeight}`)
  }

  function selectStation(id: string) {
    setStationId(id)
    setOfflineCameraIndex(1)
    if (id !== stationId) setVideoSize('— × —')
  }

  function retryCamera() {
    void sessionRef.current?.retry()
  }

  const stationOnline = station.level !== 'offline'
  const diseaseText = stationOnline ? '未见明显虫害' : '设备离线无图像'
  const diseaseConfidence = stationOnline ? 96.2 : 0
  const ndvi = stationOnline ? 0.81 : 0

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
          </div>

          <div className="camera-controls">
            <label>
              <span>选择监测站点</span>
              <select value={stationId} onChange={(event) => selectStation(event.target.value)}>
                {INSPECTION_STATIONS.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.id} · {item.name}{item.id === LIVE_STATION_ID ? `（已发现 ${devices.length} 台）` : '（未连接）'}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>{liveFeed ? `选择摄像头 · 已发现 ${devices.length} 台` : '选择摄像头'}</span>
              <select
                value={liveFeed ? selectedDeviceId : offlineCameraIndex}
                disabled={liveFeed && devices.length === 0}
                onChange={(event) => {
                  if (liveFeed) void sessionRef.current?.select(event.target.value)
                  else setOfflineCameraIndex(Number(event.target.value))
                }}
              >
                {liveFeed ? <>
                  {!selectedDevice && <option value={selectedDeviceId}>{cameraStatus === 'requesting' ? '正在检测摄像头…' : '请选择可用摄像头'}</option>}
                  {devices.map((device, index) => (
                    <option key={device.deviceId} value={device.deviceId}>
                      摄像头 {pad(index + 1)} · {device.label || `视频设备 ${index + 1}`}
                    </option>
                  ))}
                </> : Array.from({ length: station.cameraCount }, (_, index) => (
                    <option key={index + 1} value={index + 1}>摄像头 {pad(index + 1)}（未连接）</option>
                  ))}
              </select>
            </label>
            <div className="camera-controls__status">
              <span>视频状态</span>
              <strong className={cameraStatus === 'live' ? 'is-ok' : 'is-off'}>
                <i />
                {cameraStatus === 'live' ? '传输正常' : cameraStatus === 'requesting' ? '连接中' : '未连接'}
              </strong>
            </div>
          </div>
          {liveFeed && <div className="camera-discovery">
            <span role="status">{cameraMessage} · 自动检测站点摄像头，插拔后更新列表</span>
            <button type="button" onClick={() => { void sessionRef.current?.refresh() }}>重新检测</button>
          </div>}
        </section>

        <section className="recognition-panel tech-panel" aria-labelledby="recognition-title">
          <header className="inspection-section-head">
            <div>
              <span>AI RECOGNITION MATRIX</span>
              <h2 id="recognition-title">智能识别分析</h2>
            </div>
            <small>{stationOnline ? '实时分析中' : '站点离线'}</small>
          </header>

          <div className="recognition-list">
            <article className={`recognition-card recognition-card--${station.level}`}>
              <div className="recognition-card__title">
                <div>
                  <small>LEAF DAMAGE</small>
                  <h3>叶害识别</h3>
                </div>
                <span>{stationOnline ? '长势良好' : '离线'}</span>
              </div>
              <strong>{stationOnline ? '叶片完整度 94.8%' : '暂无识别结果'}</strong>
              <div className="recognition-progress"><i style={{ width: stationOnline ? '94.8%' : '0%' }} /></div>
              <p>{stationOnline ? '模型置信度 95.4% · 刚刚更新' : '等待站点重新上线'}</p>
            </article>

            <article className={`recognition-card recognition-card--${station.level}`}>
              <div className="recognition-card__title">
                <div>
                  <small>DISEASE DETECTION</small>
                  <h3>虫害识别</h3>
                </div>
                <span>{station.levelLabel}</span>
              </div>
              <strong>{diseaseText}</strong>
              <div className="recognition-progress"><i style={{ width: `${diseaseConfidence}%` }} /></div>
              <p>{stationOnline ? `模型置信度 ${diseaseConfidence}% · 图像识别` : '等待站点重新上线'}</p>
            </article>

            <article className={`recognition-card recognition-card--${station.level}`}>
              <div className="recognition-card__title">
                <div>
                  <small>MULTISPECTRAL</small>
                  <h3>多光谱识别</h3>
                </div>
                <span>NDVI</span>
              </div>
              <strong>{stationOnline ? `植被指数 ${ndvi.toFixed(2)}` : '暂无光谱数据'}</strong>
              <div className="recognition-progress"><i style={{ width: `${ndvi * 100}%` }} /></div>
              <p>{stationOnline ? '叶绿素 42.6 SPAD · 光谱分析' : '等待站点重新上线'}</p>
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
            <span><i className="is-normal" />在线</span>
            <span><i className="is-offline" />离线</span>
          </div>
        </header>

        <div className="station-status__grid">
          {INSPECTION_STATIONS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`station-status-card station-status-card--${item.level}${stationId === item.id ? ' is-selected' : ''}`}
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
              <em>{item.levelLabel}</em>
            </button>
          ))}
        </div>
      </section>
    </div>
  )
}
