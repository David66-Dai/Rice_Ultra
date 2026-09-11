import assert from 'node:assert/strict'
import test from 'node:test'
import type { HistoryArchiveState } from '../src/lib/history.ts'
import type { HistoryDailyResponse, HistoryDayData } from '@smart-rice-security/shared'
import {
  createHistorySession,
  formatArchiveNumber,
  historyMetric,
  historySpectrumPlot,
} from '../src/lib/history.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const tick = () => new Promise(resolve => setImmediate(resolve))
const range = (stationId = 'S01', startDate = '2026-01-01', endDate = '2026-01-03') => (
  { stationId, startDate, endDate, recordCount: 3 }
)

function daily(stationId = 'S01', date = '2026-01-03'): HistoryDailyResponse {
  const current: HistoryDayData = {
    stationId, date, source: 'hive',
    environment: {
      lightKlx: null, windSpeedMs: 0, rainfallMmH: null, airTemperatureC: 22.5,
      airHumidityPercent: null, soilTemperatureC: 20, soilMoisturePercent: 60,
      soilNitrogenPpm: 123, soilPhosphorusPpm: null, soilPotassiumPpm: null,
      soilPh: null, soilEcMsCm: null,
    },
    pestDisease: null, spectrum: null,
  }
  return { current, previous: null }
}

function fixture() {
  const states: HistoryArchiveState[] = []
  const calls: { path: string; signal: AbortSignal; pending: ReturnType<typeof deferred<unknown>> }[] = []
  const request = <T>(path: string, options?: { signal?: AbortSignal }) => {
    const pending = deferred<unknown>()
    calls.push({ path, signal: options!.signal!, pending })
    return pending.promise as Promise<T>
  }
  const session = createHistorySession(request, state => states.push(state), error => (
    error instanceof Error ? error.message : '读取失败'
  ))
  return { session, calls, states, state: () => states.at(-1)! }
}

test('missing and non-finite metrics stay empty; real zero and negative changes remain data', () => {
  for (const missing of [null, undefined, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.equal(formatArchiveNumber(missing, 1), '—')
    const metric = historyMetric(missing, 4, 1, 'ppm', 100)
    assert.equal(metric.value, '—')
    assert.equal(metric.delta, null)
    assert.equal(metric.percent, null)
    assert.equal(metric.note, '当日暂无归档')
  }
  assert.deepEqual(historyMetric(0, 0, 1, 'ppm', 100), {
    available: true, value: '0.0', delta: 0, percent: 0, note: '较前一日 +0.0ppm',
  })
  assert.equal(historyMetric(2, 3, 1, 'ppm', 100).note, '较前一日 -1.0ppm')
  assert.equal(historyMetric(2, null, 1, 'ppm', 100).note, '无前一日对比数据')
  assert.equal(historyMetric(-2, -1, 1, '°C', 40).value, '-2.0')
})

test('absent reflectance never generates a curve; missing bands break lines instead of becoming zero', () => {
  for (const values of [null, undefined, [], [null, null, null, null, null, null], [10, 20]]) {
    assert.deepEqual(historySpectrumPlot(values), { points: [], segments: [] })
  }
  const plot = historySpectrumPlot([0, 20, null, Number.NaN, 40, 100])
  assert.deepEqual(plot.points.map(point => point.index), [0, 1, 4, 5])
  assert.equal(plot.segments.length, 2)
  assert.equal(plot.points[0].y, 96)
  assert.equal(plot.points.at(-1)!.y, 16)
})

test('range clamps the date before daily is requested; missing specialty archives preserve real environment data', async () => {
  const f = fixture()
  const loading = f.session.load('S01', '2026-09-11')
  assert.equal(f.calls.length, 1)
  f.calls[0].pending.resolve(range())
  await tick()
  assert.equal(f.state().date, '2026-01-03')
  assert.match(f.calls[1].path, /date=2026-01-03$/)
  f.calls[1].pending.resolve(daily())
  await loading
  assert.equal(f.state().loading, false)
  assert.equal(f.state().error, null)
  assert.equal(f.state().daily!.current.environment.airTemperatureC, 22.5)
  assert.equal(f.state().daily!.current.environment.soilNitrogenPpm, 123)
  assert.equal(f.state().daily!.current.pestDisease, null)
  assert.equal(f.state().daily!.current.spectrum, null)
  f.session.dispose()
})

test('selecting another date aborts the old daily request and ignores its later error', async () => {
  const f = fixture()
  const first = f.session.load('S01', '2026-01-03')
  f.calls[0].pending.resolve(range())
  await tick()
  const next = f.session.load('S01', '2026-01-02')
  assert.equal(f.calls[1].signal.aborted, true)
  assert.equal(f.calls.length, 3) // The range is reused for the same station.
  f.calls[2].pending.resolve(daily('S01', '2026-01-02'))
  await next
  f.calls[1].pending.reject(new Error('stale failure'))
  await first
  assert.equal(f.state().date, '2026-01-02')
  assert.equal(f.state().error, null)
  assert.equal(f.state().loading, false)
  f.session.dispose()
})

test('station changes ignore an old range response and never fetch the old station daily archive', async () => {
  const f = fixture()
  const first = f.session.load('S01', '2026-01-03')
  const next = f.session.load('S02', '2026-01-03')
  f.calls[0].pending.resolve(range('S01'))
  await first
  assert.equal(f.calls.length, 2)
  f.calls[1].pending.resolve(range('S02'))
  await tick()
  assert.match(f.calls[2].path, /stationId=S02/)
  f.calls[2].pending.resolve(daily('S02'))
  await next
  assert.equal(f.state().stationId, 'S02')
  assert.equal(f.state().daily!.current.stationId, 'S02')
  f.session.dispose()
})

test('refreshing a changed range invalidates the old daily response and clears previous errors', async () => {
  const f = fixture()
  const first = f.session.load('S01', '2026-01-03')
  f.calls[0].pending.reject(new Error('range unavailable'))
  await first
  assert.equal(f.state().error, 'range unavailable')
  const retry = f.session.load('S01', '2026-01-03', true)
  assert.equal(f.state().error, null)
  f.calls[1].pending.resolve(range())
  await tick()
  const refresh = f.session.load('S01', '2026-01-03', true)
  f.calls[3].pending.resolve(range('S01', '2025-12-20', '2025-12-22'))
  await tick()
  assert.equal(f.state().date, '2025-12-22')
  f.calls[4].pending.resolve(daily('S01', '2025-12-22'))
  await refresh
  f.calls[2].pending.resolve(daily('S01', '2026-01-03'))
  await retry
  assert.equal(f.state().daily!.current.date, '2025-12-22')
  assert.equal(f.state().error, null)
  f.session.dispose()
})

test('previous-day response survives loading for field-by-field comparisons', async () => {
  const f = fixture()
  const loading = f.session.load('S01', '2026-01-03')
  f.calls[0].pending.resolve(range())
  await tick()
  const response = daily()
  response.previous = daily('S01', '2026-01-02').current
  response.previous.environment.airTemperatureC = 23.5
  f.calls[1].pending.resolve(response)
  await loading
  const { current, previous } = f.state().daily!
  assert.equal(historyMetric(current.environment.airTemperatureC, previous!.environment.airTemperatureC, 1, '°C', 40).note, '较前一日 -1.0°C')
  assert.equal(historyMetric(current.environment.soilPhosphorusPpm, previous!.environment.soilPhosphorusPpm, 1, 'ppm', 40).delta, null)
  f.session.dispose()
})

test('unmount aborts pending work and suppresses a late response', async () => {
  const f = fixture()
  const loading = f.session.load('S01', '2026-01-03')
  f.session.dispose()
  const count = f.states.length
  assert.equal(f.calls[0].signal.aborted, true)
  f.calls[0].pending.resolve(range())
  await loading
  assert.equal(f.states.length, count)
  assert.equal(f.calls.length, 1)
})

test('invalid ranges and mismatched daily identity produce an error without showing unrelated data', async () => {
  const f = fixture()
  const invalid = f.session.load('S01', '2026-01-03')
  f.calls[0].pending.resolve(range('S01', '2026-02-31', '2026-03-10'))
  await invalid
  assert.match(f.state().error!, /日期范围无效/)
  assert.equal(f.calls.length, 1)
  const loading = f.session.load('S01', '2026-01-03')
  f.calls[1].pending.resolve(range())
  await tick()
  f.calls[2].pending.resolve(daily('S02'))
  await loading
  assert.match(f.state().error!, /不一致/)
  assert.equal(f.state().daily, null)
  f.session.dispose()
})
