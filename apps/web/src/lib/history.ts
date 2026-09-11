import type { HistoryDailyResponse, HistoryRangeResponse } from '@smart-rice-security/shared'

export function isArchiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export function formatArchiveNumber(value: number | null | undefined, decimals: number): string {
  return isArchiveNumber(value) ? value.toFixed(decimals) : '—'
}

export function historyMetric(
  value: number | null | undefined,
  previous: number | null | undefined,
  decimals: number,
  unit: string,
  scaleMax: number,
) {
  const available = isArchiveNumber(value)
  const delta = available && isArchiveNumber(previous) ? value - previous : null
  return {
    available,
    value: formatArchiveNumber(value, decimals),
    percent: available ? Math.min(100, Math.max(0, value / scaleMax * 100)) : null,
    delta,
    note: !available ? '当日暂无归档'
      : delta === null ? '无前一日对比数据'
      : `${delta >= 0 ? '较前一日 +' : '较前一日 -'}${Math.abs(delta).toFixed(decimals)}${unit}`,
  }
}

// The existing archive API orders these six bands. Incomplete samples retain
// their band positions; a missing sample must never become zero or join a gap.
export const HISTORY_SPECTRAL_BANDS = [450, 550, 650, 720, 800, 900] as const

export function historySpectrumPlot(values: (number | null)[] | null | undefined) {
  const points: { index: number; x: number; y: number }[] = []
  const segments: string[] = []
  let segment: string[] = []
  const finishSegment = () => {
    if (segment.length > 1) segments.push(segment.join(' '))
    segment = []
  }
  if (!values || values.length !== HISTORY_SPECTRAL_BANDS.length) return { points, segments }
  values.forEach((value, index) => {
    if (!isArchiveNumber(value) || value < 0 || value > 100) {
      finishSegment()
      return
    }
    const point = { index, x: 20 + index * 52, y: 96 - value * 0.8 }
    points.push(point)
    segment.push(`${point.x},${point.y}`)
  })
  finishSegment()
  return { points, segments }
}

export type HistoryArchiveState = {
  stationId: string
  date: string
  range: HistoryRangeResponse | null
  daily: HistoryDailyResponse | null
  loading: boolean
  error: string | null
}

type HistoryRequest = <T>(path: string, options?: { signal?: AbortSignal }) => Promise<T>

export function initialHistoryState(stationId: string, date: string): HistoryArchiveState {
  return { stationId, date, range: null, daily: null, loading: true, error: null }
}

function validDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

/** One owner for the range -> clamped date -> daily request sequence. */
export function createHistorySession(
  request: HistoryRequest,
  onChange: (state: HistoryArchiveState) => void,
  describeError: (error: unknown) => string,
) {
  let requestVersion = 0
  let controller: AbortController | null = null
  let disposed = false
  let cachedRange: HistoryRangeResponse | null = null

  return {
    async load(stationId: string, requestedDate: string, refreshRange = false) {
      if (disposed) return
      const version = ++requestVersion
      controller?.abort()
      const currentController = new AbortController()
      controller = currentController
      const current = () => !disposed && version === requestVersion && !currentController.signal.aborted
      if (refreshRange || cachedRange?.stationId !== stationId) cachedRange = null
      let range = cachedRange
      let date = requestedDate
      const publish = (daily: HistoryDailyResponse | null, loading: boolean, error: string | null) => {
        if (current()) onChange({ stationId, date, range, daily, loading, error })
      }
      publish(null, true, null)
      try {
        if (!validDate(date)) throw new Error('所选归档日期无效，请重新选择日期')
        if (!range) {
          range = await request<HistoryRangeResponse>(`/api/history/range?stationId=${encodeURIComponent(stationId)}`, {
            signal: currentController.signal,
          })
          if (!current()) return
          if (!range || range.stationId !== stationId || !validDate(range.startDate) || !validDate(range.endDate) ||
              range.startDate > range.endDate) {
            range = null
            throw new Error('历史归档日期范围无效，请重新读取')
          }
          cachedRange = range
        }
        date = date < range.startDate ? range.startDate : date > range.endDate ? range.endDate : date
        publish(null, true, null)
        const daily = await request<HistoryDailyResponse>(
          `/api/history/daily?stationId=${encodeURIComponent(stationId)}&date=${date}`,
          { signal: currentController.signal },
        )
        if (!current()) return
        if (!daily?.current || daily.current.stationId !== stationId || daily.current.date !== date) {
          throw new Error('历史归档与所选站点或日期不一致，请重新读取')
        }
        publish(daily, false, null)
      } catch (error) {
        publish(null, false, describeError(error))
      }
    },
    dispose() {
      disposed = true
      ++requestVersion
      controller?.abort()
    },
  }
}
