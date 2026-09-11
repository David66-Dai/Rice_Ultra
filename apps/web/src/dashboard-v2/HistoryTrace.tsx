import { useEffect, useMemo, useRef, useState } from 'react'
import type {
  HistoryEnvironmentAverages,
  HistoryRangeResponse,
} from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import {
  createHistorySession,
  formatArchiveNumber,
  HISTORY_SPECTRAL_BANDS,
  historyMetric,
  historySpectrumPlot,
  initialHistoryState,
  isArchiveNumber,
} from '../lib/history.ts'
import './HistoryTrace.css'

type MetricDefinition = {
  key: string
  field: keyof HistoryEnvironmentAverages
  icon: string
  label: string
  unit: string
  decimals: number
  scaleMax: number
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
}))

const METRIC_DEFINITIONS: MetricDefinition[] = [
  { key: 'light', field: 'lightKlx', icon: '☀', label: '日均光照强度', unit: 'klx', decimals: 4, scaleMax: 0.6 },
  { key: 'wind', field: 'windSpeedMs', icon: '↝', label: '日平均风速', unit: 'm/s', decimals: 1, scaleMax: 8 },
  { key: 'temperature', field: 'airTemperatureC', icon: '℃', label: '日平均空气温度', unit: '°C', decimals: 1, scaleMax: 40 },
  { key: 'humidity', field: 'airHumidityPercent', icon: 'RH', label: '日平均空气湿度', unit: '%RH', decimals: 0, scaleMax: 100 },
  { key: 'soil-temperature', field: 'soilTemperatureC', icon: '℃', label: '日平均土壤温度', unit: '°C', decimals: 1, scaleMax: 40 },
  { key: 'soil-moisture', field: 'soilMoisturePercent', icon: 'H₂O', label: '日平均土壤湿度', unit: '%', decimals: 1, scaleMax: 100 },
  { key: 'nitrogen', field: 'soilNitrogenPpm', icon: 'N', label: '日均土壤氮浓度', unit: 'ppm', decimals: 1, scaleMax: 180 },
  { key: 'phosphorus', field: 'soilPhosphorusPpm', icon: 'P', label: '日均土壤磷浓度', unit: 'ppm', decimals: 1, scaleMax: 50 },
  { key: 'potassium', field: 'soilPotassiumPpm', icon: 'K', label: '日均土壤钾浓度', unit: 'ppm', decimals: 0, scaleMax: 180 },
  { key: 'ph', field: 'soilPh', icon: 'pH', label: '日均土壤酸碱度', unit: 'pH', decimals: 2, scaleMax: 10 },
  { key: 'conductivity', field: 'soilEcMsCm', icon: 'EC', label: '日均土壤电导率', unit: 'mS/cm', decimals: 2, scaleMax: 2 },
]

function atStartOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function addDays(date: Date, days: number) {
  const result = new Date(date)
  result.setDate(result.getDate() + days)
  return atStartOfDay(result)
}

function toDateValue(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseDateValue(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function formatFullDate(date: Date) {
  return `${date.getFullYear()}年${String(date.getMonth() + 1).padStart(2, '0')}月${String(date.getDate()).padStart(2, '0')}日`
}

function clampDate(date: Date, range: HistoryRangeResponse | null, today: Date) {
  const maxDate = range ? parseDateValue(range.endDate) : today
  const minDate = range ? parseDateValue(range.startDate) : date
  if (date.getTime() > maxDate.getTime()) return maxDate
  if (date.getTime() < minDate.getTime()) return minDate
  return date
}

export function HistoryTrace() {
  const { request } = useAuth()
  const today = useMemo(() => atStartOfDay(new Date()), [])
  const [archive, setArchive] = useState(() => initialHistoryState('S01', toDateValue(today)))
  const selection = useRef({ stationId: 'S01', date: toDateValue(today) })
  const session = useRef<ReturnType<typeof createHistorySession> | null>(null)
  const { stationId: selectedStationId, date: selectedValue, range, daily, loading, error } = archive
  const selectedDate = useMemo(() => parseDateValue(selectedValue), [selectedValue])
  const [stationMenuOpen, setStationMenuOpen] = useState(false)
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]
  const timelineDates = useMemo(
    () => Array.from({ length: 9 }, (_, index) => addDays(selectedDate, index - 4)),
    [selectedDate],
  )
  const archiveCode = `${selectedStation.id}-${selectedValue.replaceAll('-', '')}`
  const maxDateValue = range?.endDate ?? toDateValue(today)
  const minDateValue = range?.startDate
  const metrics = useMemo(() => {
    if (!daily) return []
    return METRIC_DEFINITIONS.map((definition) => {
      const value = daily.current.environment[definition.field]
      const previous = daily.previous?.environment[definition.field]
      return {
        ...definition,
        ...historyMetric(value, previous, definition.decimals, definition.unit, definition.scaleMax),
      }
    })
  }, [daily])
  const pestDisease = daily?.current.pestDisease
  const spectrum = daily?.current.spectrum
  const spectralPlot = historySpectrumPlot(spectrum?.reflectancePercent)
  const hasPestArchive = Boolean(pestDisease && Object.values(pestDisease).some(isArchiveNumber))
  const hasSpectrumArchive = Boolean(spectrum && (
    [spectrum.ndvi, spectrum.ndre, spectrum.gndvi, spectrum.chlorophyllSpad].some(isArchiveNumber) || spectralPlot.points.length
  ))

  useEffect(() => {
    const activeSession = createHistorySession(request, (state) => {
      selection.current = { stationId: state.stationId, date: state.date }
      setArchive(state)
    }, describeError)
    session.current = activeSession
    void activeSession.load(selection.current.stationId, selection.current.date)
    return () => activeSession.dispose()
  }, [request])

  function selectStation(stationId: string) {
    if (stationId === selectedStationId) {
      setStationMenuOpen(false)
      return
    }
    setStationMenuOpen(false)
    void session.current?.load(stationId, selectedValue)
  }

  function selectDate(date: Date) {
    const nextDate = clampDate(date, range, today)
    if (toDateValue(nextDate) === selectedValue) return
    void session.current?.load(selectedStationId, toDateValue(nextDate))
  }

  function retryRequest() {
    void session.current?.load(selectedStationId, selectedValue, true)
  }

  return (
    <div className="history-page">
      <section className="history-timeline history-panel" aria-labelledby="history-title">
        <header className="history-head">
          <div>
            <span>HISTORICAL DATA TRACEABILITY</span>
            <h2 id="history-title">历史数据溯源</h2>
          </div>
          <div className="history-head__controls">
            <div className="station-switch">
              <button
                type="button"
                className="station-switch__trigger"
                onClick={() => setStationMenuOpen((open) => !open)}
                aria-expanded={stationMenuOpen}
                aria-haspopup="listbox"
              >
                <span className="station-switch__status" aria-hidden="true"><i /></span>
                <span>
                  <small>归档站点</small>
                  <strong>{selectedStation.id} · {selectedStation.name}</strong>
                </span>
                <b aria-hidden="true">⌄</b>
              </button>

              {stationMenuOpen && (
                <div className="station-switch__menu" role="listbox" aria-label="选择历史数据站点">
                  {STATIONS.map((station) => (
                    <button
                      key={station.id}
                      type="button"
                      className={station.id === selectedStationId ? 'is-selected' : ''}
                      onClick={() => selectStation(station.id)}
                      role="option"
                      aria-selected={station.id === selectedStationId}
                    >
                      <i aria-hidden="true" />
                      <span><strong>{station.id}</strong>{station.name}</span>
                      <em>查询</em>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <span>
              <i aria-hidden="true" />{selectedStation.id} 历史归档 · 按日查询
            </span>
            <label>
              <small>选择年月日</small>
              <input
                type="date"
                value={selectedValue}
                min={minDateValue}
                max={maxDateValue}
                onChange={(event) => {
                  if (event.target.value) selectDate(parseDateValue(event.target.value))
                }}
                aria-label="选择溯源日期"
              />
            </label>
            <button type="button" onClick={() => selectDate(parseDateValue(maxDateValue))} disabled={selectedValue === maxDateValue}>
              最新数据
            </button>
          </div>
        </header>

        <div className="timeline-control">
          <button
            type="button"
            className="timeline-arrow"
            onClick={() => selectDate(addDays(selectedDate, -7))}
            disabled={Boolean(minDateValue && selectedValue <= minDateValue)}
            aria-label="查看前一周"
          >
            <span>‹</span><small>PREV</small>
          </button>

          <div className="timeline-track" aria-label="日期时间轴">
            {timelineDates.map((date) => {
              const value = toDateValue(date)
              const selected = value === selectedValue
              const unavailable = value > maxDateValue || Boolean(minDateValue && value < minDateValue)
              return (
                <button
                  key={value}
                  type="button"
                  className={`${selected ? 'is-selected' : ''}${unavailable ? ' is-future' : ''}`}
                  onClick={() => selectDate(date)}
                  disabled={unavailable || loading}
                  aria-current={selected ? 'date' : undefined}
                  aria-label={`查看${formatFullDate(date)}数据`}
                >
                  <small>{date.getFullYear()}</small>
                  <strong>{String(date.getMonth() + 1).padStart(2, '0')}/{String(date.getDate()).padStart(2, '0')}</strong>
                  <span><i />{WEEKDAYS[date.getDay()]}</span>
                </button>
              )
            })}
          </div>

          <button
            type="button"
            className="timeline-arrow"
            onClick={() => selectDate(addDays(selectedDate, 7))}
            disabled={selectedValue >= maxDateValue}
            aria-label="查看后一周"
          >
            <span>›</span><small>NEXT</small>
          </button>
        </div>
      </section>

      <section className="history-data history-panel" aria-labelledby="history-data-title">
        <header className="history-data__head">
          <div>
            <span>DAILY AVERAGE ARCHIVE</span>
            <h2 id="history-data-title">{formatFullDate(selectedDate)} · 日平均数据</h2>
          </div>
          {daily && (
            <div className="history-archive">
              <span>归档编号</span>
              <strong>{archiveCode}</strong>
              <em>{daily.current.source === 'hive' ? 'Hive 归档' : '数据库记录'}{range ? ` · ${range.recordCount} 天` : ''}</em>
            </div>
          )}
        </header>

        {loading ? (
          <div className="history-empty" role="status">
            <i className="history-loading" aria-hidden="true" />
            <strong>正在读取历史数据库</strong>
            <p>{selectedStation.id} · {selectedValue}</p>
          </div>
        ) : error ? (
          <div className="history-empty history-empty--error" role="alert">
            <strong>历史数据读取失败</strong>
            <p>{error}</p>
            <button type="button" onClick={retryRequest}>重新读取</button>
          </div>
        ) : daily ? (
          <>
            <div className="history-metrics">
              {metrics.map((metric) => (
                <article key={metric.key} className={!metric.available ? 'is-missing' : metric.delta === null ? '' : metric.delta < 0 ? 'is-down' : 'is-up'}>
                  <header>
                    <span aria-hidden="true">{metric.icon}</span>
                    <div><small>DAILY AVG</small><h3>{metric.label}</h3></div>
                    <em aria-label={metric.delta === null ? '暂无对比数据' : metric.delta === 0 ? '与前一日相同' : metric.delta > 0 ? '高于前一日' : '低于前一日'}>
                      {metric.delta === null ? '—' : metric.delta === 0 ? '→' : metric.delta > 0 ? '↗' : '↘'}
                    </em>
                  </header>
                  <strong>{metric.value}<small>{metric.unit}</small></strong>
                  <div className="history-metric__bar" aria-hidden="true">
                    {metric.percent !== null && <i style={{ width: `${metric.percent}%` }} />}
                  </div>
                  <footer>
                    <span>{metric.note}</span>
                    <small>{metric.available ? '日均值' : '缺少记录'}</small>
                  </footer>
                </article>
              ))}
            </div>

            <div className="history-special">
              <article className="history-special__card history-special__card--pest">
                <header>
                  <div>
                    <small>PEST & DISEASE ARCHIVE</small>
                    <h3>病虫害监测数据</h3>
                  </div>
                  <span>{!hasPestArchive ? '暂无归档' : !isArchiveNumber(pestDisease?.riskIndex) ? '暂无风险指数' : pestDisease.riskIndex < 12 ? '低风险' : '需关注'}</span>
                </header>
                {hasPestArchive && pestDisease ? <div className="pest-data">
                  <div className="pest-data__result">
                    <span className="pest-data__radar" aria-hidden="true"><i /></span>
                    <div>
                      <small>AI 识别结论</small>
                      <strong>{!isArchiveNumber(pestDisease.diseaseCount) ? '暂无病害识别归档' : pestDisease.diseaseCount === 0 ? '未检出明显病害' : `发现 ${pestDisease.diseaseCount} 处疑似病斑`}</strong>
                      <p>识别置信度 {formatArchiveNumber(pestDisease.recognitionConfidencePercent, 1)}%</p>
                    </div>
                  </div>
                  <dl>
                    <div><dt>疑似病斑</dt><dd>{formatArchiveNumber(pestDisease.diseaseCount, 0)}<small>处</small></dd></div>
                    <div><dt>虫口密度</dt><dd>{formatArchiveNumber(pestDisease.pestDensityPer100Plants, 1)}<small>头/百株</small></dd></div>
                    <div><dt>受害面积</dt><dd>{formatArchiveNumber(pestDisease.affectedAreaPercent, 1)}<small>%</small></dd></div>
                    <div><dt>风险指数</dt><dd>{formatArchiveNumber(pestDisease.riskIndex, 1)}<small>/100</small></dd></div>
                  </dl>
                </div> : <div className="history-special__empty" role="status">
                  <strong>暂无病虫害归档</strong>
                  <p>该日未提供病虫害监测记录</p>
                </div>}
              </article>

              <article className="history-special__card history-special__card--spectrum">
                <header>
                  <div>
                    <small>MULTISPECTRAL ARCHIVE</small>
                    <h3>多光谱监测数据</h3>
                  </div>
                  <span>{!hasSpectrumArchive ? '暂无归档' : spectralPlot.points.length === HISTORY_SPECTRAL_BANDS.length ? '反射率已归档' : '部分指标归档'}</span>
                </header>
                {hasSpectrumArchive && spectrum ? <div className="spectrum-data">
                  <div className="spectrum-chart">
                    {spectralPlot.points.length ? <svg viewBox="0 0 300 112" role="img" aria-label="历史光谱反射率曲线；缺失波段留空">
                      {[24, 48, 72, 96].map((y) => <line key={y} x1="20" y1={y} x2="280" y2={y} />)}
                      {spectralPlot.segments.map((points) => <polyline key={points} points={points} />)}
                      {spectralPlot.points.map((point) => (
                        <circle key={point.index} cx={point.x} cy={point.y} r="3" />
                      ))}
                      {HISTORY_SPECTRAL_BANDS.map((label, index) => (
                        <text key={label} x={20 + index * 52} y="108" textAnchor="middle">{label}</text>
                      ))}
                    </svg> : <p className="spectrum-chart__empty">暂无反射率归档</p>}
                    {spectralPlot.points.length > 0 && <small>波长 / nm · 反射率曲线</small>}
                  </div>
                  <dl>
                    <div><dt>NDVI</dt><dd>{formatArchiveNumber(spectrum.ndvi, 2)}</dd></div>
                    <div><dt>NDRE</dt><dd>{formatArchiveNumber(spectrum.ndre, 2)}</dd></div>
                    <div><dt>GNDVI</dt><dd>{formatArchiveNumber(spectrum.gndvi, 2)}</dd></div>
                    <div><dt>叶绿素</dt><dd>{formatArchiveNumber(spectrum.chlorophyllSpad, 1)}<small>SPAD</small></dd></div>
                  </dl>
                </div> : <div className="history-special__empty" role="status">
                  <strong>暂无多光谱归档</strong>
                  <p>该日未提供多光谱监测记录</p>
                </div>}
              </article>
            </div>
          </>
        ) : (
          <div className="history-empty" role="status">
            <span>无日平均数据记录</span>
          </div>
        )}
      </section>
    </div>
  )
}
