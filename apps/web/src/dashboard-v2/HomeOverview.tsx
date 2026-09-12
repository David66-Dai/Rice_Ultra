import { useEffect, useMemo, useState } from 'react'
import type { RealtimeSensorReading, StationAlertLevel } from '@smart-rice-security/shared'
import riceArea from '../assets/rice_area.png'
import { useAuth } from '../auth/useAuth.ts'
import { REALTIME_SYNC_INTERVAL_MS } from '../lib/realtime'
import { stationLevelLabel, stationPointClass } from '../lib/station-alerts.ts'
import { useStationAlerts } from './useStationAlerts.ts'
import './HomeOverview.css'

type Station = {
  id: string
  name: string
  left: number
  top: number
  online: boolean
}

const INITIAL_STATIONS: Station[] = Array.from({ length: 10 }, (_, index) => {
  const row = Math.floor(index / 5)
  const column = index % 5
  return {
    id: `S${String(index + 1).padStart(2, '0')}`,
    name: `${index + 1}号监测站`,
    left: 18 + column * 16,
    top: 28 + row * 32,
    online: index === 0,
  }
})

const POSITION_STORAGE_KEY = 'smart-rice-station-positions'

function loadStations(): Station[] {
  try {
    const raw = window.localStorage.getItem(POSITION_STORAGE_KEY)
    if (!raw) return INITIAL_STATIONS
    const saved = JSON.parse(raw) as Record<string, { left: number; top: number }>
    return INITIAL_STATIONS.map((station) => {
      const position = saved[station.id]
      return position ? { ...station, left: position.left, top: position.top } : station
    })
  } catch {
    return INITIAL_STATIONS
  }
}

const FIELD_METRICS = [
  { key: 'sun', field: 'lightKlx', icon: '☀', label: '实时光照强度', unit: 'klx', decimals: 3, scaleMax: 100 },
  { key: 'wind', field: 'windSpeedMs', icon: '↝', label: '实时风速', unit: 'm/s', decimals: 1, scaleMax: 15 },
  { key: 'rain', field: 'rainfallMmH', icon: '◌', label: '天气降雨强度', unit: 'mm/h', decimals: 1, scaleMax: 10 },
  { key: 'temp', field: 'airTemperatureC', icon: '℃', label: '实时空气温度', unit: '°C', decimals: 1, scaleMax: 45 },
  { key: 'humidity', field: 'airHumidityPercent', icon: 'RH', label: '实时空气湿度', unit: '%RH', decimals: 0, scaleMax: 100 },
  { key: 'nitrogen', field: 'soilNitrogenMgKg', icon: 'N', label: '土壤氮含量', unit: 'mg/kg', decimals: 0, scaleMax: 220 },
  { key: 'phosphorus', field: 'soilPhosphorusMgKg', icon: 'P', label: '土壤磷含量', unit: 'mg/kg', decimals: 0, scaleMax: 220 },
  { key: 'potassium', field: 'soilPotassiumMgKg', icon: 'K', label: '土壤钾含量', unit: 'mg/kg', decimals: 0, scaleMax: 220 },
  { key: 'ph', field: 'soilPh', icon: 'pH', label: '土壤酸碱度', unit: 'pH', decimals: 1, scaleMax: 10 },
  { key: 'conductivity', field: 'soilEcMsCm', icon: 'EC', label: '土壤电导率', unit: 'mS/cm', decimals: 1, scaleMax: 3 },
] as const

function alertClass(alert: StationAlertLevel | undefined) {
  if (alert === 'red') return 'is-alert-red'
  if (alert === 'yellow') return 'is-alert-yellow'
  return ''
}

export function HomeOverview() {
  const auth = useAuth()
  const { byId, stations: alertStations } = useStationAlerts()
  const [stations] = useState<Station[]>(loadStations)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [latest, setLatest] = useState<RealtimeSensorReading | null>(null)
  const alertCount = alertStations.filter((item) => item.alertLevel === 'red' || item.alertLevel === 'yellow').length

  const selected = useMemo(
    () => stations.find((station) => station.id === selectedId) ?? null,
    [selectedId, stations],
  )
  const selectedAlert = selected ? byId[selected.id] : undefined
  const selectedAlertLevel = selectedAlert?.alertLevel
  const metrics = useMemo(() => FIELD_METRICS.map((metric) => {
    const value = latest?.[metric.field]
    return {
      ...metric,
      value: value == null ? '--' : value.toFixed(metric.decimals),
      percent: value == null ? 0 : Math.min(100, Math.max(2, (value / metric.scaleMax) * 100)),
      note: value == null
        ? '等待串口数据'
        : metric.key === 'rain'
          ? 'Open-Meteo 当前小时'
          : '传感器实时采集',
    }
  }), [latest])

  useEffect(() => {
    if (selectedId !== 'S01') return
    let cancelled = false
    let inFlight = false
    const controller = new AbortController()

    function loadLatest() {
      if (cancelled || inFlight) return
      inFlight = true
      auth.request<RealtimeSensorReading>('/api/realtime/latest?stationId=S01', { signal: controller.signal })
        .then((reading) => {
          if (!cancelled) setLatest(reading)
        })
        .catch(() => {
          // 串口尚未形成完整数据帧时保持空值
        })
        .finally(() => { inFlight = false })
    }

    loadLatest()
    const timer = window.setInterval(loadLatest, REALTIME_SYNC_INTERVAL_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      controller.abort()
    }
  }, [auth, selectedId])

  function selectStation(station: Station) {
    setSelectedId(station.id)
    if (!station.online) setLatest(null)
  }

  return (
    <div className="home-overview">
      <section className="station-map" aria-labelledby="station-map-title">
        <header className="station-map__head">
          <div className="station-map__title">
            <span className="section-kicker">FIELD DIGITAL TWIN</span>
            <h2 id="station-map-title">农田站点实时监控</h2>
          </div>

          <aside className="station-detail" aria-live="polite">
            <div className="station-detail__name">
              <small>当前站点</small>
              <strong>{selected?.name ?? '请选择监测站点'}</strong>
            </div>
            <span className={selectedAlertLevel === 'red' || selectedAlertLevel === 'yellow' ? alertClass(selectedAlertLevel) : selected?.online ? 'is-online' : 'is-offline'}>
              {selected
                ? (selectedAlertLevel === 'red' || selectedAlertLevel === 'yellow'
                  ? stationLevelLabel(selected.online, selectedAlertLevel)
                  : selected.online ? '设备在线' : '设备离线')
                : '等待选择'}
            </span>
            <dl>
              <div>
                <dt>站点编号</dt>
                <dd>{selected?.id ?? '--'}</dd>
              </div>
              <div>
                <dt>信号状态</dt>
                <dd>{selected?.online ? '强' : '--'}</dd>
              </div>
              <div>
                <dt>土壤墒情</dt>
                <dd>--</dd>
              </div>
              <div>
                <dt>数据更新</dt>
                <dd>{selected?.online && latest ? new Date(latest.sampledAt).toLocaleTimeString('zh-CN', { hour12: false }) : '--'}</dd>
              </div>
            </dl>
          </aside>

          <div className="station-map__summary">
            <span><i className="status-dot status-dot--online" />在线 <b>1</b></span>
            <span><i className="status-dot status-dot--offline" />离线 <b>9</b></span>
            <span><i className="status-dot status-dot--alert" />告警 <b>{alertCount}</b></span>
            <strong>站点总数 <b>10</b></strong>
          </div>
        </header>

        <div className="station-map__stage">
          <img src={riceArea} alt="农田航拍监控底图" />
          <div className="station-map__overlay" aria-hidden="true" />

          {stations.map((station) => {
            const alert = byId[station.id]?.alertLevel
            return (
              <button
                key={station.id}
                type="button"
                className={stationPointClass(station.online, alert, selectedId === station.id)}
                style={{ left: `${station.left}%`, top: `${station.top}%` }}
                title={`${station.name} · ${stationLevelLabel(station.online, alert)}`}
                aria-label={`${station.name}，${stationLevelLabel(station.online, alert)}`}
                onClick={() => selectStation(station)}
              >
                <i aria-hidden="true" />
                <span>{station.id}</span>
              </button>
            )
          })}

          <div className="station-map__legend">
            <span><i className="status-dot status-dot--online" />在线正常</span>
            <span><i className="status-dot status-dot--yellow" />黄色预警</span>
            <span><i className="status-dot status-dot--alert" />红色告警</span>
            <span><i className="status-dot status-dot--offline" />离线站点</span>
            <em>点击站点查看监控详情</em>
          </div>
        </div>
      </section>

      {!selected && (
        <section className="field-data-prompt" aria-label="实时数据查看引导">
          <div className="field-data-prompt__scanner" aria-hidden="true">
            <span><i /></span>
          </div>
          <div className="field-data-prompt__message">
            <small>REAL-TIME DATA ACCESS</small>
            <h2>选择地图站点，接入实时感知数据</h2>
            <p>点击上方站点标记后，将在此处展示对应站点的环境与土壤实时采集结果</p>
          </div>
          <div className="field-data-prompt__steps">
            <span><i>01</i><strong>选择站点</strong><small>点击地图标记</small></span>
            <b aria-hidden="true">›</b>
            <span><i>02</i><strong>建立链路</strong><small>识别在线设备</small></span>
            <b aria-hidden="true">›</b>
            <span><i>03</i><strong>查看数据</strong><small>环境 · 土壤 · 天气</small></span>
          </div>
          <div className="field-data-prompt__status">
            <span><i />可用站点</span>
            <strong>1<small>/10</small></strong>
          </div>
        </section>
      )}

      {selected && <section className="field-data" aria-labelledby="field-data-title">
        <header className="field-data__head">
          <div>
            <span className="section-kicker">REAL-TIME FIELD DATA</span>
            <h2 id="field-data-title">{selected.name} · 农田环境与土壤实时数据</h2>
          </div>
          <time>
            {selected.online && latest
              ? `最新采样：${new Date(latest.sampledAt).toLocaleTimeString('zh-CN', { hour12: false })}`
              : selected.online ? 'COM4：等待完整数据帧' : '站点离线'}
          </time>
        </header>

        {selected.online ? <div className="field-data__grid">
          {metrics.map((metric) => (
            <article key={metric.key} className="metric-card metric-card--flat">
              <div className="metric-card__top">
                <span className="metric-card__icon" aria-hidden="true">{metric.icon}</span>
                <span className="metric-card__label">{metric.label}</span>
              </div>
              <strong>
                {metric.value}
                <small>{metric.unit}</small>
              </strong>
              <div className="metric-card__track" aria-hidden="true">
                <i style={{ width: `${metric.percent}%` }} />
              </div>
              <p>{metric.note}</p>
            </article>
          ))}
        </div> : (
          <div className="field-data__empty" role="status">
            <span>{selected.name}当前离线，暂无实时采集数据</span>
          </div>
        )}
      </section>}
    </div>
  )
}
