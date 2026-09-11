import { useId } from 'react'

type DonutSlice = { value: number; color: string }

export function DonutChart({
  percent,
  label,
  slices,
  size = 118,
}: {
  percent: number
  label: string
  slices: DonutSlice[]
  size?: number
}) {
  const stroke = 12
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const total = slices.reduce((sum, s) => sum + s.value, 0) || 1
  const arcs = slices.reduce<{ color: string; len: number; offset: number }[]>((acc, slice) => {
    const len = (slice.value / total) * c
    const offset = acc.reduce((sum, a) => sum + a.len, 0)
    acc.push({ color: slice.color, len, offset })
    return acc
  }, [])

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="chart-donut" aria-hidden="true">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth={stroke} />
      {arcs.map((arc) => (
        <circle
          key={arc.color}
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={arc.color}
          strokeWidth={stroke}
          strokeDasharray={`${arc.len} ${c - arc.len}`}
          strokeDashoffset={-arc.offset}
          strokeLinecap="butt"
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      ))}
      <text x="50%" y="46%" textAnchor="middle" className="chart-donut__num">
        {percent}%
      </text>
      <text x="50%" y="62%" textAnchor="middle" className="chart-donut__cap">
        {label}
      </text>
    </svg>
  )
}

export function GaugeChart({ value, size = 132 }: { value: number; size?: number }) {
  const gid = `gauge-${useId().replace(/:/g, '')}`
  const stroke = 11
  const r = (size - stroke) / 2 - 4
  const c = 2 * Math.PI * r
  const track = c * 0.75
  const fill = track * (value / 100)

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="chart-gauge" aria-hidden="true">
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke="rgba(255,255,255,0.07)"
        strokeWidth={stroke}
        strokeDasharray={`${track} ${c}`}
        strokeLinecap="round"
        transform={`rotate(135 ${size / 2} ${size / 2})`}
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke={`url(#${gid})`}
        strokeWidth={stroke}
        strokeDasharray={`${fill} ${c}`}
        strokeLinecap="round"
        transform={`rotate(135 ${size / 2} ${size / 2})`}
      />
      <defs>
        <linearGradient id={gid} x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#1ba7bf" />
          <stop offset="1" stopColor="#7deaf6" />
        </linearGradient>
      </defs>
      <text x="50%" y="50%" textAnchor="middle" className="chart-gauge__num">
        {value}
      </text>
      <text x="50%" y="64%" textAnchor="middle" className="chart-gauge__cap">
        长势指数
      </text>
    </svg>
  )
}

export function Sparkline({ points, color }: { points: number[]; color: string }) {
  const w = 160
  const h = 46
  const min = Math.min(...points)
  const max = Math.max(...points)
  const span = max - min || 1
  const coords = points.map((p, i) => {
    const x = (i / Math.max(points.length - 1, 1)) * w
    const y = h - 4 - ((p - min) / span) * (h - 10)
    return `${x},${y}`
  })
  const line = coords.join(' ')
  const area = `0,${h} ${line} ${w},${h}`

  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="chart-spark" aria-hidden="true">
      <polygon points={area} fill={color} opacity="0.16" />
      <polyline points={line} fill="none" stroke={color} strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}
