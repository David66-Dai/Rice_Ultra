import type {
  PestDiseaseAlertLevel,
  PestDiseaseCategory,
  PestDiseaseItem,
  PestDiseaseSummary,
} from '@smart-rice-security/shared'

export type PestDiseaseCatalogEntry = {
  key: string
  category: PestDiseaseCategory
  label: string
  unit: string
}

/**
 * The three diseases and three pests both dashboards show, in this order. It mirrors
 * PestDiseaseCatalog on the server, so a day filled in here lines up with a day the backend
 * summarised. The list is fixed: an item with no data keeps its place and reports no value.
 */
export const PEST_DISEASE_CATALOG: PestDiseaseCatalogEntry[] = [
  { key: 'bacterial_leaf_blight', category: 'disease', label: '细菌性叶枯病', unit: '次' },
  { key: 'brown_spot', category: 'disease', label: '褐斑病', unit: '次' },
  { key: 'tungro_virus', category: 'disease', label: '东格鲁病毒', unit: '次' },
  { key: 'rice_planthopper', category: 'pest', label: '稻飞虱', unit: '只' },
  { key: 'striped_stem_borer', category: 'pest', label: '二化螟', unit: '只' },
  { key: 'rice_leaf_roller', category: 'pest', label: '稻纵卷叶螟', unit: '只' },
]

/** Same thresholds as the field inspection station light: see AlertLevel on the server. */
export function pestAlertLevel(category: PestDiseaseCategory, value: number | null): PestDiseaseAlertLevel {
  if (value === null || !Number.isFinite(value) || value <= 0) return 'green'
  if (category === 'disease') return 'red'
  return value >= 2 ? 'red' : 'yellow'
}

export const PEST_ALERT_LABELS: Record<PestDiseaseAlertLevel, string> = {
  green: '正常', yellow: '注意', red: '告警',
}

/** Sample data covers this closed range; the current field day always comes from the backend. */
export const MOCK_START_DATE = '2026-05-01'
export const MOCK_END_DATE = '2026-09-11'

export const SOURCE_INSPECTION = 'inspection_diagnosis'
export const SOURCE_MOCK = 'mock'

type GrowthPhase = {
  label: string
  /** Inclusive start; the phase runs until the next entry begins. */
  from: string
  /** Per catalogue key: how often something is found, and the most that is ever found. */
  pressure: Record<string, [chance: number, max: number]>
}

/**
 * Phase boundaries match farm.env_daily.growth_stage for 2026, which is identical across all ten
 * stations, so a sampled day agrees with the 生长周期 the decision board reads from Hive.
 */
const GROWTH_PHASES: GrowthPhase[] = [
  {
    label: '育秧期', from: '2026-04-15',
    pressure: {
      bacterial_leaf_blight: [0.02, 1], brown_spot: [0.02, 1], tungro_virus: [0.01, 1],
      rice_planthopper: [0.06, 2], striped_stem_borer: [0.04, 1], rice_leaf_roller: [0.03, 1],
    },
  },
  {
    label: '分蘖期', from: '2026-05-21',
    pressure: {
      bacterial_leaf_blight: [0.12, 1], brown_spot: [0.10, 1], tungro_virus: [0.05, 1],
      rice_planthopper: [0.35, 4], striped_stem_borer: [0.30, 3], rice_leaf_roller: [0.25, 3],
    },
  },
  {
    label: '拔节期', from: '2026-07-01',
    pressure: {
      bacterial_leaf_blight: [0.22, 2], brown_spot: [0.20, 2], tungro_virus: [0.12, 1],
      rice_planthopper: [0.50, 6], striped_stem_borer: [0.42, 4], rice_leaf_roller: [0.40, 4],
    },
  },
  {
    label: '孕穗期', from: '2026-07-16',
    pressure: {
      bacterial_leaf_blight: [0.35, 2], brown_spot: [0.32, 2], tungro_virus: [0.20, 2],
      rice_planthopper: [0.62, 8], striped_stem_borer: [0.50, 5], rice_leaf_roller: [0.55, 6],
    },
  },
  {
    label: '抽穗期', from: '2026-07-26',
    pressure: {
      bacterial_leaf_blight: [0.45, 3], brown_spot: [0.40, 3], tungro_virus: [0.28, 2],
      rice_planthopper: [0.70, 10], striped_stem_borer: [0.55, 6], rice_leaf_roller: [0.62, 7],
    },
  },
  {
    label: '灌浆期', from: '2026-08-11',
    pressure: {
      bacterial_leaf_blight: [0.30, 2], brown_spot: [0.28, 2], tungro_virus: [0.18, 2],
      rice_planthopper: [0.48, 6], striped_stem_borer: [0.38, 4], rice_leaf_roller: [0.42, 5],
    },
  },
  {
    label: '成熟期', from: '2026-09-11',
    pressure: {
      bacterial_leaf_blight: [0.12, 1], brown_spot: [0.12, 1], tungro_virus: [0.08, 1],
      rice_planthopper: [0.22, 3], striped_stem_borer: [0.18, 2], rice_leaf_roller: [0.20, 2],
    },
  },
]

export function growthPhaseLabel(date: string): string | null {
  let current: string | null = null
  for (const phase of GROWTH_PHASES) {
    if (date >= phase.from) current = phase.label
  }
  return current
}

function phaseFor(date: string): GrowthPhase {
  let current = GROWTH_PHASES[0]
  for (const phase of GROWTH_PHASES) {
    if (date >= phase.from) current = phase
  }
  return current
}

/** FNV-1a, so the same station and day always produce the same figure on every device and reload. */
function hash32(value: string): number {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash >>> 0
}

function unitInterval(seed: string): number {
  return hash32(seed) / 0x1_0000_0000
}

function sampledValue(stationId: string, date: string, phase: GrowthPhase, key: string): number {
  const [chance, max] = phase.pressure[key] ?? [0, 0]
  if (chance <= 0 || max <= 0) return 0
  const draw = unitInterval(`${stationId}|${date}|${key}`)
  if (draw > chance) return 0
  // Draws nearer zero sit deeper inside the phase pressure, so they read as heavier days.
  const magnitude = (chance - draw) / chance
  return Math.min(max, 1 + Math.floor(magnitude * max))
}

function isValidDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

export function hasMockPestDisease(date: string): boolean {
  return isValidDate(date) && date >= MOCK_START_DATE && date <= MOCK_END_DATE
}

/**
 * Sample figures for one earlier day. Deterministic in the station and the date, so the card does
 * not change when the page is reloaded or the same day is revisited.
 */
export function mockPestDisease(stationId: string, date: string): PestDiseaseSummary | null {
  if (!/^S(?:0[1-9]|10)$/.test(stationId) || !hasMockPestDisease(date)) return null
  const phase = phaseFor(date)
  const items: PestDiseaseItem[] = PEST_DISEASE_CATALOG.map(entry => {
    const value = sampledValue(stationId, date, phase, entry.key)
    return {
      key: entry.key,
      category: entry.category,
      label: entry.label,
      unit: entry.unit,
      value,
      alertLevel: pestAlertLevel(entry.category, value),
    }
  })
  const found = items.filter(item => (item.value ?? 0) > 0).length
  return {
    available: true,
    source: SOURCE_MOCK,
    sourceTable: '',
    stationId,
    referenceDate: date,
    lastDiagnosedAt: null,
    // Inspections still happen on quiet days, so this never drops to zero.
    recognitionCount: 2 + found + (hash32(`${stationId}|${date}|inspections`) % 3),
    items,
    notes: [
      `往期病虫害为演示用模拟数据（${MOCK_START_DATE} 至 ${MOCK_END_DATE}），按 ${phase.label} 的发生规律生成，不是实测记录。`,
      '病害为当日识别次数，虫害为当日检出只数，与当天巡检记录口径一致。',
      '当天数据来自田间巡检识别记录；切换到当天即可查看实测结果。',
    ],
  }
}

/** Catalogue rows with no value, so a day without data still renders the whole card. */
export function emptyPestDisease(stationId: string, date: string, notes: string[]): PestDiseaseSummary {
  return {
    available: false,
    source: 'none',
    sourceTable: '',
    stationId,
    referenceDate: date,
    lastDiagnosedAt: null,
    recognitionCount: 0,
    items: PEST_DISEASE_CATALOG.map(entry => ({
      key: entry.key, category: entry.category, label: entry.label, unit: entry.unit,
      value: null, alertLevel: 'green' as PestDiseaseAlertLevel,
    })),
    notes,
  }
}

/**
 * Real same-day figures always win. Any earlier day inside the sample range is filled in here;
 * anything else keeps whatever the backend returned so the card never claims data it does not have.
 */
export function resolvePestDisease(
  summary: PestDiseaseSummary | null | undefined,
  stationId: string,
  date: string,
): PestDiseaseSummary | null {
  if (summary?.source === SOURCE_INSPECTION) return summary
  return mockPestDisease(stationId, date) ?? summary ?? null
}

export function pestDiseaseSourceLabel(summary: PestDiseaseSummary | null | undefined): string {
  if (!summary) return '暂无数据'
  if (summary.source === SOURCE_INSPECTION) return summary.available ? '当天巡检记录' : '当天暂无识别'
  if (summary.source === SOURCE_MOCK) return '往期模拟数据'
  return '暂无数据'
}

/** Worst level across the catalogue, for the card header badge. */
export function pestDiseaseOverallLevel(summary: PestDiseaseSummary | null | undefined): PestDiseaseAlertLevel {
  if (!summary?.available) return 'green'
  if (summary.items.some(item => item.alertLevel === 'red')) return 'red'
  if (summary.items.some(item => item.alertLevel === 'yellow')) return 'yellow'
  return 'green'
}
