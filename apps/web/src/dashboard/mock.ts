export type PlotStatus = 'normal' | 'warning' | 'offline'

export type Plot = {
  id: string
  name: string
  area: number
  crop: string
  variety: string
  stage: string
  harvest: string
  devices: number
  devicesOnline: number
  status: PlotStatus
  growth: number
  /** 航拍图上的点击热区（百分比） */
  hotspot: { left: number; top: number; width: number; height: number }
}

export type EnvMetric = {
  key: string
  label: string
  value: string
  unit: string
  delta: string
  trend: 'up' | 'down' | 'flat'
  tone: 'cyan' | 'green' | 'gold' | 'warn'
}

export type WarningRank = {
  rank: number
  name: string
  count: number
}

export type Suggestion = {
  title: string
  detail: string
  tone: 'gold' | 'warn' | 'cyan'
}

export type TrendSeries = {
  key: string
  label: string
  unit: string
  color: string
  points: number[]
}

export const PLOTS: Plot[] = [
  { id: 'ST-001', name: '1号田', area: 42.6, crop: '水稻', variety: '甬优 1540', stage: '分蘖期', harvest: '2026-10-18', devices: 4, devicesOnline: 4, status: 'normal', growth: 78, hotspot: { left: 16, top: 12, width: 16.5, height: 36 } },
  { id: 'ST-002', name: '3号田', area: 38.2, crop: '水稻', variety: '甬优 1540', stage: '分蘖期', harvest: '2026-10-20', devices: 3, devicesOnline: 3, status: 'normal', growth: 81, hotspot: { left: 33, top: 12, width: 16.5, height: 36 } },
  { id: 'ST-003', name: '5号田', area: 51.0, crop: '水稻', variety: '甬优 12', stage: '拔节期', harvest: '2026-10-12', devices: 5, devicesOnline: 4, status: 'warning', growth: 69, hotspot: { left: 50, top: 12, width: 16.5, height: 36 } },
  { id: 'ST-004', name: '7号田', area: 29.4, crop: '水稻', variety: '甬优 1540', stage: '返青期', harvest: '2026-10-28', devices: 2, devicesOnline: 0, status: 'offline', growth: 54, hotspot: { left: 67, top: 12, width: 16.5, height: 36 } },
  { id: 'ST-005', name: '4号田', area: 46.8, crop: '水稻', variety: '甬优 1540', stage: '拔节期', harvest: '2026-10-15', devices: 4, devicesOnline: 4, status: 'normal', growth: 88, hotspot: { left: 16, top: 50, width: 16.5, height: 36 } },
  { id: 'ST-006', name: '6号田', area: 33.7, crop: '水稻', variety: '甬优 12', stage: '孕穗期', harvest: '2026-10-08', devices: 3, devicesOnline: 3, status: 'normal', growth: 91, hotspot: { left: 33, top: 50, width: 16.5, height: 36 } },
  { id: 'ST-007', name: '8号田', area: 62.9, crop: '水稻', variety: '甬优 1540', stage: '拔节期', harvest: '2026-10-16', devices: 3, devicesOnline: 2, status: 'warning', growth: 72, hotspot: { left: 50, top: 50, width: 16.5, height: 36 } },
  { id: 'ST-008', name: '2号田', area: 15.3, crop: '水稻', variety: '甬优 1540', stage: '拔节期', harvest: '2026-10-22', devices: 4, devicesOnline: 4, status: 'normal', growth: 86, hotspot: { left: 67, top: 50, width: 16.5, height: 36 } },
]

export const ALERT_FEED = [
  { time: '09:42', plot: 'ST-003', text: '纹枯病风险升高，建议巡田复核', tone: 'warn' as const },
  { time: '09:18', plot: 'ST-007', text: '稻纵卷叶螟监测值超阈值', tone: 'warn' as const },
  { time: '08:55', plot: 'ST-004', text: '田间传感器离线，等待恢复', tone: 'off' as const },
  { time: '08:21', plot: 'ST-001', text: '土壤含水率偏低，保持浅水层', tone: 'gold' as const },
  { time: '07:46', plot: 'ST-006', text: '长势指数 91，生育进程正常', tone: 'ok' as const },
]

export const OVERVIEW = {
  area: 320,
  plots: 10,
  devicesOnline: 1,
  warnings: 3,
}

export const DEVICE_STATS = {
  online: 1,
  offline: 9,
  total: 10,
}

export const ENV_METRICS: EnvMetric[] = [
  { key: 'temp', label: '空气温度', value: '28.6', unit: '°C', delta: '较昨日 -1.2°C', trend: 'down', tone: 'cyan' },
  { key: 'humi', label: '空气湿度', value: '78', unit: '%', delta: '较昨日 +4%', trend: 'up', tone: 'green' },
  { key: 'soil', label: '土壤含水率', value: '32', unit: '%', delta: '适宜', trend: 'flat', tone: 'gold' },
  { key: 'light', label: '光照强度', value: '65200', unit: 'Lux', delta: '较昨日 +12%', trend: 'up', tone: 'warn' },
]

export const WARNING_RANKS: WarningRank[] = [
  { rank: 1, name: '稻纵卷叶螟', count: 12 },
  { rank: 2, name: '纹枯病', count: 8 },
  { rank: 3, name: '稻飞虱', count: 5 },
  { rank: 4, name: '二化螟', count: 3 },
  { rank: 5, name: '稻瘟病', count: 2 },
]

export const GROWTH_STAGES = ['移栽', '分蘖', '拔节', '孕穗', '抽穗', '成熟'] as const

export const SUGGESTIONS: Suggestion[] = [
  { title: '水肥管理', detail: '建议 7 日内追施穗肥，亩施尿素 8kg', tone: 'gold' },
  { title: '病虫害防治', detail: '重点监测稻纵卷叶螟，必要时喷施生物农药', tone: 'warn' },
  { title: '田间管理', detail: '清理排水沟，保持浅水层 3–5cm', tone: 'cyan' },
]

export const TASK_STATS = {
  done: 12,
  doing: 3,
  todo: 4,
  total: 19,
}

export const MAP_LAYERS = [
  { id: 'live', label: '实时监测' },
  { id: 'soil', label: '土壤墒情' },
  { id: 'growth', label: '作物长势' },
  { id: 'pest', label: '虫害分布' },
] as const

export type MapLayer = (typeof MAP_LAYERS)[number]['id']

export const TREND_RANGES = [
  { id: '24h', label: '近24小时' },
  { id: '7d', label: '近7天' },
  { id: '30d', label: '近30天' },
] as const

export type TrendRange = (typeof TREND_RANGES)[number]['id']

const SERIES_24H: TrendSeries[] = [
  { key: 'temp', label: '空气温度', unit: '°C', color: '#46d7ea', points: [26.2, 25.8, 25.4, 26.1, 27.4, 28.8, 29.6, 28.6] },
  { key: 'humi', label: '空气湿度', unit: '%', color: '#3ee08f', points: [82, 84, 86, 83, 79, 76, 74, 78] },
  { key: 'soil', label: '土壤含水', unit: '%', color: '#7deaf6', points: [30, 31, 31, 32, 32, 33, 32, 32] },
  { key: 'light', label: '光照强度', unit: 'kLux', color: '#e8bd5a', points: [0.2, 4, 18, 42, 58, 65, 52, 28] },
  { key: 'wind', label: '风速', unit: 'm/s', color: '#8bb4ff', points: [1.2, 1.6, 2.1, 1.8, 2.4, 2.0, 1.7, 1.5] },
  { key: 'water', label: '水位', unit: 'cm', color: '#5ad0c8', points: [4.2, 4.4, 4.6, 4.5, 4.8, 5.0, 4.7, 4.6] },
]

const SERIES_7D: TrendSeries[] = [
  { key: 'temp', label: '空气温度', unit: '°C', color: '#46d7ea', points: [27.1, 28.4, 29.0, 27.8, 26.9, 27.6, 28.6] },
  { key: 'humi', label: '空气湿度', unit: '%', color: '#3ee08f', points: [74, 71, 69, 76, 80, 79, 78] },
  { key: 'soil', label: '土壤含水', unit: '%', color: '#7deaf6', points: [34, 33, 31, 30, 31, 32, 32] },
  { key: 'light', label: '光照强度', unit: 'kLux', color: '#e8bd5a', points: [48, 61, 66, 52, 44, 58, 65] },
  { key: 'wind', label: '风速', unit: 'm/s', color: '#8bb4ff', points: [1.8, 2.2, 1.4, 1.1, 1.9, 2.3, 1.5] },
  { key: 'water', label: '水位', unit: 'cm', color: '#5ad0c8', points: [5.2, 4.8, 4.4, 4.1, 4.5, 4.7, 4.6] },
]

const SERIES_30D: TrendSeries[] = [
  { key: 'temp', label: '空气温度', unit: '°C', color: '#46d7ea', points: [24, 25, 26, 25, 27, 28, 29, 28, 27, 28] },
  { key: 'humi', label: '空气湿度', unit: '%', color: '#3ee08f', points: [81, 79, 76, 74, 72, 75, 78, 80, 77, 78] },
  { key: 'soil', label: '土壤含水', unit: '%', color: '#7deaf6', points: [36, 35, 34, 33, 32, 31, 32, 33, 32, 32] },
  { key: 'light', label: '光照强度', unit: 'kLux', color: '#e8bd5a', points: [38, 44, 51, 49, 55, 62, 58, 54, 60, 65] },
  { key: 'wind', label: '风速', unit: 'm/s', color: '#8bb4ff', points: [2.4, 2.1, 1.8, 1.6, 2.0, 2.2, 1.7, 1.4, 1.8, 1.5] },
  { key: 'water', label: '水位', unit: 'cm', color: '#5ad0c8', points: [6.0, 5.6, 5.2, 4.8, 4.4, 4.2, 4.5, 4.8, 4.6, 4.6] },
]

export const TREND_DATA: Record<TrendRange, TrendSeries[]> = {
  '24h': SERIES_24H,
  '7d': SERIES_7D,
  '30d': SERIES_30D,
}

export const TREND_RANGE_LABEL: Record<TrendRange, string> = {
  '24h': '2026-09-08 09:00 — 2026-09-09 09:00',
  '7d': '2026-09-02 — 2026-09-09',
  '30d': '2026-08-10 — 2026-09-09',
}

export const STATUS_LABEL: Record<PlotStatus, string> = {
  normal: '正常',
  warning: '预警',
  offline: '离线',
}

export function findPlot(id: string): Plot {
  return PLOTS.find((p) => p.id === id) ?? PLOTS[7]
}
