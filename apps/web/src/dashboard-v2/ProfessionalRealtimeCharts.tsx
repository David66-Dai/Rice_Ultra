import type { RealtimeSensorReading } from '@smart-rice-security/shared'

export type ProfessionalChartId = 'microclimate' | 'light' | 'weather' | 'npk' | 'soil-chemistry'

type ValueGetter = (reading: RealtimeSensorReading) => number | null

function points(readings: RealtimeSensorReading[], getter: ValueGetter, min: number, max: number) {
  const range = max - min
  return readings
    .map((reading, index) => {
      const value = getter(reading)
      if (value == null) return null
      const x = readings.length === 1 ? 250 : 30 + (index * 440) / (readings.length - 1)
      const normalized = Math.min(1, Math.max(0, (value - min) / range))
      return { x, y: 188 - normalized * 132, value }
    })
    .filter((point): point is NonNullable<typeof point> => point !== null)
}

function pointString(series: ReturnType<typeof points>) {
  return series.map((point) => `${point.x},${point.y}`).join(' ')
}

function timeLabels(readings: RealtimeSensorReading[]) {
  if (readings.length === 0) return ['00:00', '06:00', '12:00', '18:00', '24:00']
  const indexes = [0, 0.25, 0.5, 0.75, 1].map((ratio) => Math.round((readings.length - 1) * ratio))
  return indexes.map((index) => new Date(readings[index].sampledAt).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }))
}

function ChartFrame({
  readings,
  hasData,
  children,
}: {
  readings: RealtimeSensorReading[]
  hasData: boolean
  children: React.ReactNode
}) {
  const labels = timeLabels(readings)
  return (
    <div className="professional-chart">
      <svg viewBox="0 0 500 230" aria-hidden="true">
        <rect x="30" y="56" width="440" height="132" className="professional-chart__plot" />
        {[56, 89, 122, 155, 188].map((y) => (
          <line key={y} x1="30" y1={y} x2="470" y2={y} className="professional-chart__grid" />
        ))}
        {children}
        {labels.map((label, index) => (
          <text key={`${label}-${index}`} x={30 + index * 110} y="211" textAnchor="middle" className="professional-chart__axis-label">
            {label}
          </text>
        ))}
      </svg>
      {!hasData && (
        <div className="professional-chart__empty">
          <i />
          <strong>等待当天传感器数据</strong>
          <span>串口采集数据写入后自动生成图表</span>
        </div>
      )}
    </div>
  )
}

function Legend({ items }: { items: Array<{ name: string; color: string; unit: string }> }) {
  return (
    <div className="professional-chart__legend">
      {items.map((item) => (
        <span key={item.name} style={{ '--legend-color': item.color } as React.CSSProperties}>
          <i />{item.name}<small>{item.unit}</small>
        </span>
      ))}
    </div>
  )
}

function MicroclimateChart({ readings }: { readings: RealtimeSensorReading[] }) {
  const temperature = points(readings, (item) => item.airTemperatureC, 0, 45)
  const humidity = points(readings, (item) => item.airHumidityPercent, 0, 100)
  return (
    <>
      <Legend items={[
        { name: '空气温度', color: '#ffb45a', unit: '℃' },
        { name: '空气湿度', color: '#4de5ff', unit: '%RH' },
      ]} />
      <ChartFrame readings={readings} hasData={temperature.length > 0 || humidity.length > 0}>
        <polyline points={pointString(temperature)} className="chart-series chart-series--temperature" />
        <polyline points={pointString(humidity)} className="chart-series chart-series--humidity" />
      </ChartFrame>
    </>
  )
}

function LightChart({ readings }: { readings: RealtimeSensorReading[] }) {
  const light = points(readings, (item) => item.lightKlx, 0, 100)
  const area = light.length > 0 ? `30,188 ${pointString(light)} 470,188` : ''
  return (
    <>
      <Legend items={[{ name: '实时光照强度', color: '#ffd35a', unit: 'klx' }]} />
      <ChartFrame readings={readings} hasData={light.length > 0}>
        <defs>
          <linearGradient id="light-area-gradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#ffd35a" stopOpacity="0.38" />
            <stop offset="100%" stopColor="#ffd35a" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <polygon points={area} fill="url(#light-area-gradient)" />
        <polyline points={pointString(light)} className="chart-series chart-series--light" />
      </ChartFrame>
    </>
  )
}

function WeatherChart({ readings }: { readings: RealtimeSensorReading[] }) {
  const rain = points(readings, (item) => item.rainfallMmH, 0, 10)
  const wind = points(readings, (item) => item.windSpeedMs, 0, 15)
  return (
    <>
      <Legend items={[
        { name: '降雨强度', color: '#4d8cff', unit: 'mm/h' },
        { name: '风速', color: '#65e1dd', unit: 'm/s' },
      ]} />
      <ChartFrame readings={readings} hasData={rain.length > 0 || wind.length > 0}>
        {rain.map((point) => (
          <rect key={point.x} x={point.x - 3} y={point.y} width="6" height={188 - point.y} className="chart-rain-bar" />
        ))}
        <polyline points={pointString(wind)} className="chart-series chart-series--wind" />
      </ChartFrame>
    </>
  )
}

function NpkChart({ readings }: { readings: RealtimeSensorReading[] }) {
  const nitrogen = points(readings, (item) => item.soilNitrogenMgKg, 0, 220)
  const phosphorus = points(readings, (item) => item.soilPhosphorusMgKg, 0, 220)
  const potassium = points(readings, (item) => item.soilPotassiumMgKg, 0, 220)
  return (
    <>
      <Legend items={[
        { name: '氮 N', color: '#55e2a2', unit: 'mg/kg' },
        { name: '磷 P', color: '#ffd35a', unit: 'mg/kg' },
        { name: '钾 K', color: '#b48cff', unit: 'mg/kg' },
      ]} />
      <ChartFrame readings={readings} hasData={nitrogen.length > 0 || phosphorus.length > 0 || potassium.length > 0}>
        <polyline points={pointString(nitrogen)} className="chart-series chart-series--nitrogen" />
        <polyline points={pointString(phosphorus)} className="chart-series chart-series--phosphorus" />
        <polyline points={pointString(potassium)} className="chart-series chart-series--potassium" />
      </ChartFrame>
    </>
  )
}

function SoilChemistryChart({ readings }: { readings: RealtimeSensorReading[] }) {
  const ph = points(readings, (item) => item.soilPh, 4, 9)
  const ec = points(readings, (item) => item.soilEcMsCm, 0, 3)
  return (
    <>
      <Legend items={[
        { name: '土壤 pH', color: '#b48cff', unit: 'pH' },
        { name: '土壤 EC', color: '#4de5ff', unit: 'mS/cm' },
      ]} />
      <ChartFrame readings={readings} hasData={ph.length > 0 || ec.length > 0}>
        <rect x="30" y="109" width="440" height="27" className="chart-control-zone" />
        <polyline points={pointString(ph)} className="chart-series chart-series--ph" />
        <polyline points={pointString(ec)} className="chart-series chart-series--ec" />
      </ChartFrame>
    </>
  )
}

export function ProfessionalRealtimeChart({
  chartId,
  readings,
}: {
  chartId: ProfessionalChartId
  readings: RealtimeSensorReading[]
}) {
  if (chartId === 'microclimate') return <MicroclimateChart readings={readings} />
  if (chartId === 'light') return <LightChart readings={readings} />
  if (chartId === 'weather') return <WeatherChart readings={readings} />
  if (chartId === 'npk') return <NpkChart readings={readings} />
  return <SoilChemistryChart readings={readings} />
}
