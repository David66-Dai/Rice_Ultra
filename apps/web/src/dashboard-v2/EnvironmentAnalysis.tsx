import { useEffect, useMemo, useRef, useState } from 'react'
import type { RealtimeSensorReading, RealtimeTodayResponse } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import { REALTIME_SYNC_INTERVAL_MS } from '../lib/realtime'
import {
  ProfessionalRealtimeChart,
} from './ProfessionalRealtimeCharts.tsx'
import type { ProfessionalChartId } from './ProfessionalRealtimeCharts.tsx'
import './EnvironmentAnalysis.css'

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
  online: index === 0,
}))

const CHARTS = [
  { id: 'microclimate', code: 'MICROCLIMATE DUAL AXIS', title: '农田微气候双轴趋势', color: '#4de5ff' },
  { id: 'light', code: 'SOLAR RADIATION INTEGRAL', title: '光照强度面积积分图', color: '#ffd35a' },
  { id: 'weather', code: 'WIND & PRECIPITATION', title: '风雨耦合柱线图', color: '#55b8ff' },
  { id: 'npk', code: 'SOIL NPK BALANCE', title: '土壤氮磷钾养分谱', color: '#55e2a2' },
  { id: 'soil-chemistry', code: 'SOIL CHEMISTRY CONTROL', title: '土壤 pH-EC 控制图', color: '#b48cff' },
] as const satisfies ReadonlyArray<{ id: ProfessionalChartId; code: string; title: string; color: string }>

const INDEX_DATA = [
  {
    code: 'CROP HEALTH INDEX',
    title: '作物健康指数',
    color: '#4ce39b',
  },
  {
    code: 'SOIL ACTIVITY INDEX',
    title: '土壤活性指数',
    color: '#4dccff',
  },
] as const

function valueScore(value: number, ideal: number, tolerance: number) {
  return Math.max(0, Math.min(100, 100 - (Math.abs(value - ideal) / tolerance) * 45))
}

function calculatedIndices(latest: RealtimeSensorReading | undefined) {
  if (!latest) return null
  const cropValues = [
    latest.airTemperatureC,
    latest.airHumidityPercent,
    latest.soilNitrogenMgKg,
    latest.soilPhosphorusMgKg,
    latest.soilPotassiumMgKg,
  ]
  const soilValues = [
    latest.soilNitrogenMgKg,
    latest.soilPhosphorusMgKg,
    latest.soilPotassiumMgKg,
    latest.soilPh,
    latest.soilEcMsCm,
  ]
  if (cropValues.some((value) => value == null) || soilValues.some((value) => value == null)) return null

  const crop = Math.round((
    valueScore(latest.airTemperatureC!, 27, 12)
    + valueScore(latest.airHumidityPercent!, 78, 35)
    + valueScore(latest.soilNitrogenMgKg!, 90, 70)
    + valueScore(latest.soilPhosphorusMgKg!, 35, 28)
    + valueScore(latest.soilPotassiumMgKg!, 125, 90)
  ) / 5)
  const soil = Math.round((
    valueScore(latest.soilNitrogenMgKg!, 90, 70)
    + valueScore(latest.soilPhosphorusMgKg!, 35, 28)
    + valueScore(latest.soilPotassiumMgKg!, 125, 90)
    + valueScore(latest.soilPh!, 6.5, 2.5)
    + valueScore(latest.soilEcMsCm!, 0.7, 1.4)
  ) / 5)
  return [crop, soil]
}

function chartHeadline(chartId: string, latest: RealtimeSensorReading | undefined) {
  if (!latest) return '--'
  if (chartId === 'microclimate') {
    return latest.airTemperatureC == null || latest.airHumidityPercent == null
      ? '--'
      : `${latest.airTemperatureC.toFixed(1)}℃ / ${latest.airHumidityPercent.toFixed(0)}%`
  }
  if (chartId === 'light') return latest.lightKlx == null ? '--' : `${latest.lightKlx.toFixed(2)} klx`
  if (chartId === 'weather') {
    return latest.windSpeedMs == null || latest.rainfallMmH == null
      ? '--'
      : `${latest.windSpeedMs.toFixed(1)} m/s · ${latest.rainfallMmH.toFixed(1)} mm/h`
  }
  if (chartId === 'npk') {
    return latest.soilNitrogenMgKg == null || latest.soilPhosphorusMgKg == null || latest.soilPotassiumMgKg == null
      ? '--'
      : `N ${latest.soilNitrogenMgKg.toFixed(0)} · P ${latest.soilPhosphorusMgKg.toFixed(0)} · K ${latest.soilPotassiumMgKg.toFixed(0)}`
  }
  return latest.soilPh == null || latest.soilEcMsCm == null
    ? '--'
    : `pH ${latest.soilPh.toFixed(2)} · EC ${latest.soilEcMsCm.toFixed(2)}`
}

function wheelPosition(index: number, activeIndex: number) {
  let offset = index - activeIndex
  if (offset > 2) offset -= CHARTS.length
  if (offset < -2) offset += CHARTS.length
  return offset
}

export function EnvironmentAnalysis() {
  const auth = useAuth()
  const [activeIndex, setActiveIndex] = useState(0)
  const [selectedStationId, setSelectedStationId] = useState('S01')
  const [stationMenuOpen, setStationMenuOpen] = useState(false)
  const [readings, setReadings] = useState<RealtimeSensorReading[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retryKey, setRetryKey] = useState(0)
  const wheelLockRef = useRef(0)
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]
  const latest = readings.at(-1)
  const indices = useMemo(() => calculatedIndices(latest), [latest])

  useEffect(() => {
    if (!selectedStation.online) return
    let cancelled = false
    let inFlight = false
    const controller = new AbortController()

    function loadToday() {
      if (cancelled || inFlight) return
      inFlight = true
      auth.request<RealtimeTodayResponse>(`/api/realtime/today?stationId=${selectedStationId}`, { signal: controller.signal })
        .then((response) => {
          if (cancelled) return
          setReadings(response.readings)
          setLoading(false)
          setError(null)
        })
        .catch((requestError: unknown) => {
          if (cancelled) return
          setLoading(false)
          setError(describeError(requestError))
        })
        .finally(() => { inFlight = false })
    }

    loadToday()
    const timer = window.setInterval(loadToday, REALTIME_SYNC_INTERVAL_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      controller.abort()
    }
  }, [auth, retryKey, selectedStation.online, selectedStationId])

  function move(direction: -1 | 1) {
    setActiveIndex((current) => (current + direction + CHARTS.length) % CHARTS.length)
  }

  function handleWheel(event: React.WheelEvent<HTMLDivElement>) {
    const now = Date.now()
    if (now - wheelLockRef.current < 420) return
    if (Math.abs(event.deltaY) < 12 && Math.abs(event.deltaX) < 12) return
    wheelLockRef.current = now
    move(event.deltaY + event.deltaX > 0 ? 1 : -1)
  }

  function selectStation(stationId: string) {
    if (stationId === selectedStationId) {
      setStationMenuOpen(false)
      return
    }
    const station = STATIONS.find((item) => item.id === stationId)
    setSelectedStationId(stationId)
    setStationMenuOpen(false)
    setReadings([])
    setError(null)
    setLoading(Boolean(station?.online))
  }

  function retryReadings() {
    setLoading(true)
    setError(null)
    setRetryKey((value) => value + 1)
  }

  return (
    <div className="environment-page">
      <section className="environment-visual tech-panel" aria-labelledby="environment-title">
        <header className="environment-head">
          <div>
            <span>ENVIRONMENT DATA VISUALIZATION</span>
            <h2 id="environment-title">环境分析</h2>
          </div>
          <div className="environment-head__meta">
            <div className="station-switch">
              <button
                type="button"
                className="station-switch__trigger"
                onClick={() => setStationMenuOpen((open) => !open)}
                aria-expanded={stationMenuOpen}
                aria-haspopup="listbox"
              >
                <span className={`station-switch__status${selectedStation.online ? '' : ' is-offline'}`}><i /></span>
                <span>
                  <small>当前站点</small>
                  <strong>{selectedStation.id} · {selectedStation.name}</strong>
                </span>
                <b aria-hidden="true">⌄</b>
              </button>

              {stationMenuOpen && (
                <div className="station-switch__menu" role="listbox" aria-label="选择环境监测站点">
                  {STATIONS.map((station) => (
                    <button
                      key={station.id}
                      type="button"
                      className={`${station.id === selectedStationId ? 'is-selected' : ''}${station.online ? '' : ' is-offline'}`}
                      onClick={() => selectStation(station.id)}
                      role="option"
                      aria-selected={station.id === selectedStationId}
                    >
                      <i />
                      <span><strong>{station.id}</strong>{station.name}</span>
                      <em>{station.online ? '在线' : '离线'}</em>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <span className={selectedStation.online ? '' : 'is-offline'}>
              <i />{selectedStation.id} {selectedStation.online ? (readings.length > 0 ? '实时采集中' : '等待传感器') : '站点离线'}
            </span>
            {selectedStation.online && (
              <strong>{String(activeIndex + 1).padStart(2, '0')} / 05</strong>
            )}
          </div>
        </header>

        {selectedStation.online ? (
          <>
            {error && (
              <div className="environment-source-error" role="alert">
                <span>实时数据接口连接失败：{error}</span>
                <button type="button" onClick={retryReadings}>重新连接</button>
              </div>
            )}
            <div
              className="chart-wheel"
              onWheel={handleWheel}
              onKeyDown={(event) => {
                if (event.key === 'ArrowLeft') move(-1)
                if (event.key === 'ArrowRight') move(1)
              }}
              tabIndex={0}
              aria-label="环境图表轮播，使用左右方向键或鼠标滚轮切换"
            >
              <button className="chart-wheel__button chart-wheel__button--previous" type="button" onClick={() => move(-1)} aria-label="上一张图表">
                <span>‹</span>
                <small>PREV</small>
              </button>

              <div className="chart-wheel__viewport">
                {CHARTS.map((chart, index) => {
                  const position = wheelPosition(index, activeIndex)
                  return (
                    <article
                      key={chart.id}
                      className={`chart-card is-position-${position < 0 ? `n${Math.abs(position)}` : position}`}
                      style={{ '--chart-color': chart.color } as React.CSSProperties}
                      aria-hidden={index !== activeIndex}
                    >
                      <header className="chart-card__head">
                        <div>
                          <small>{chart.code}</small>
                          <h3>{chart.title}</h3>
                        </div>
                        <div className="chart-card__reading">
                          <strong>{chartHeadline(chart.id, latest)}</strong>
                        </div>
                      </header>
                      <ProfessionalRealtimeChart chartId={chart.id} readings={readings} />
                      <footer>
                        <span><i />{loading ? '正在连接传感器' : readings.length > 0 ? '当天实时采集' : '等待串口数据'}</span>
                        <p>
                          {latest
                            ? `${readings.length} 条记录 · 最新 ${new Date(latest.sampledAt).toLocaleTimeString('zh-CN', { hour12: false })}`
                            : '图表仅使用数据库中的当天真实记录'}
                        </p>
                      </footer>
                    </article>
                  )
                })}
              </div>

              <button className="chart-wheel__button chart-wheel__button--next" type="button" onClick={() => move(1)} aria-label="下一张图表">
                <span>›</span>
                <small>NEXT</small>
              </button>
            </div>

            <div className="chart-wheel__dots" aria-label="选择图表">
              {CHARTS.map((chart, index) => (
                <button
                  key={chart.id}
                  type="button"
                  className={index === activeIndex ? 'is-active' : ''}
                  onClick={() => setActiveIndex(index)}
                  aria-label={`查看${chart.title}`}
                  aria-current={index === activeIndex}
                >
                  <i />
                </button>
              ))}
            </div>
          </>
        ) : (
          <div className="environment-empty" role="status">
            <i aria-hidden="true" />
            <strong>{selectedStation.name}当前离线</strong>
            <p>该站点未接入，暂无环境监测图表与数据</p>
          </div>
        )}
      </section>

      <section className="environment-indices tech-panel" aria-labelledby="environment-index-title">
        <header className="environment-head environment-head--compact">
          <div>
            <span>FIELD ECOLOGICAL INDEX</span>
            <h2 id="environment-index-title">综合生态指数</h2>
          </div>
          <small>{selectedStation.online ? '依据实时环境及土壤数据综合计算' : '站点离线，指数暂不可用'}</small>
        </header>

        {selectedStation.online && indices ? (
          <div className="index-grid">
            {INDEX_DATA.map((item, index) => (
              <article
                key={item.title}
                className="index-card"
                style={{ '--index-color': item.color } as React.CSSProperties}
              >
                <div className="index-card__name">
                  <small>{item.code}</small>
                  <div><h3>{item.title}</h3><span>{indices[index] >= 80 ? '良好' : '关注'}</span></div>
                </div>
                <div className="index-card__bar" role="meter" aria-label={item.title} aria-valuemin={0} aria-valuemax={100} aria-valuenow={indices[index]}>
                  <i style={{ width: `${indices[index]}%` }} />
                  <span style={{ left: `${indices[index]}%` }} />
                </div>
                <strong>{indices[index]}<small>/100</small></strong>
                <p>依据最新一条真实传感器记录计算</p>
              </article>
            ))}
          </div>
        ) : (
          <div className="index-empty" role="status">
            <span>{selectedStation.online ? '等待完整传感器数据后计算两项指数' : '作物健康指数 · 土壤活性指数均无数据'}</span>
          </div>
        )}
      </section>
    </div>
  )
}
