import assert from 'node:assert/strict'
import test from 'node:test'
import type { AiAnalysisJob, AiAnalysisRequest, AiEvidence } from '@smart-rice-security/shared'
import { canStartDecision, createDecisionSession, formatEvidenceNumber, formatReportTime, isDecisionBusy, isReportExpired, reportGeneratedDate } from '../src/lib/decision.ts'
import type { DecisionState } from '../src/lib/decision.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
const tick = () => new Promise(resolve => setImmediate(resolve))
const selection = (stationId = 'S01', date = ''): AiAnalysisRequest => ({ stationId, date, windowDays: 7, growthStage: 'unknown' })
const range = (stationId = 'S01') => ({ stationId, startDate: '2026-01-01', endDate: '2026-01-30', recordCount: 30 })
function evidence(stationId = 'S01', endDate = '2026-01-30'): AiEvidence {
  return {
    stationId, hiveStation: stationId === 'S01' ? 'point_1' : 'point_2', startDate: '2026-01-24', endDate,
    windowDays: 7, observedDays: 6, missingDates: ['2026-01-25'], rawRowCount: 6,
    growthStage: 'unknown', growthStageLabel: null, growthStageSource: 'unknown',
    metrics: [{ field: 'temperature_celsius', label: '温度', unit: '°C', count: 6, missingCount: 1,
      mean: 20, min: 19, max: 22, first: 19, last: 22, change: 3 }],
    daily: [{ date: endDate, values: { temperature_celsius: 22 } }], limitations: ['生长周期未提供'],
  }
}
function job(status: AiAnalysisJob['status'] = 'queued', id = 'job-fixture'): AiAnalysisJob {
  return {
    id, stationId: 'S01', date: '2026-01-30', windowDays: 7, status,
    createdAt: '2026-01-30T12:00:00Z', completedAt: status === 'succeeded' ? '2026-01-30T12:01:00Z' : null,
    error: status === 'failed' ? '分析暂不可用' : null,
    result: status === 'succeeded' ? { evidence: evidence(), weatherAnalysis: '环境分析', soilAnalysis: '土壤分析',
      riskAnalysis: '风险分析', summary: '综合建议', workflowRunId: 'workflow-fixture' } : null,
    generatedAt: status === 'succeeded' ? '2026-01-30T12:01:00Z' : null,
    expiresAt: status === 'succeeded' ? '2026-01-30T13:01:00Z' : null,
    expired: false, archiveId: status === 'succeeded' ? 'S01_20260130_2001' : null,
    archivePath: status === 'succeeded' ? '/rice/output/point_1/output_20260130_2001.json' : null,
  }
}
function fixture(options: { reports?: AiAnalysisJob[]; automaticLists?: boolean } = {}) {
  const initialReports = options.reports ?? []
  const automaticLists = options.automaticLists !== false
  const states: DecisionState[] = []
  const calls: { path: string; method?: string; body?: unknown; signal: AbortSignal; pending: ReturnType<typeof deferred<unknown>> }[] = []
  const timers = new Map<number, { callback: () => void; delay: number }>()
  let timerId = 0
  let currentTime = Date.parse('2026-01-30T12:01:00Z')
  const session = createDecisionSession(<T>(path: string, options?: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal }) => {
    const pending = deferred<unknown>()
    calls.push({ path, method: options?.method, body: options?.body, signal: options!.signal!, pending })
    if (path.startsWith('/api/ai/analyses?') && automaticLists) pending.resolve(initialReports)
    return pending.promise as Promise<T>
  }, state => states.push(state), error => error instanceof Error ? error.message : '读取失败', {
    schedule(callback, delay) { timers.set(++timerId, { callback, delay }); return timerId },
    cancel(handle) { timers.delete(handle as number) },
  }, () => currentTime)
  const state = () => states.at(-1)!
  const nextTimer = () => {
    const entry = timers.entries().next().value
    assert.ok(entry, 'Expected a scheduled poll')
    const [id, timer] = entry
    timers.delete(id)
    assert.equal(timer.delay, 1800)
    timer.callback()
  }
  async function ready(configured = true, data = evidence()) {
    const status = session.refreshStatus()
    calls.at(-1)!.pending.resolve({ configured })
    await status
    const loading = session.load(selection())
    calls.at(-1)!.pending.resolve(range())
    await tick()
    calls.at(-1)!.pending.resolve(data)
    await loading
  }
  return { session, calls, states, state, timers, nextTimer, ready, setTime: (time: number) => { currentTime = time } }
}

test('initialization and evidence refresh never automatically submit AI; latest available date is the default', async () => {
  const f = fixture()
  assert.equal(f.calls.length, 0)
  await f.ready()
  assert.equal(f.state().selection.date, '2026-01-30')
  assert.match(f.calls.at(-1)!.path, /\/api\/ai\/evidence\?stationId=S01&date=2026-01-30&windowDays=7&growthStage=unknown/)
  assert.equal(canStartDecision(f.state()), true)
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  assert.equal(f.timers.size, 0)
  f.session.dispose()
})

test('manual start is guarded synchronously against double clicks and posts the exact selected context', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  await f.session.start()
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
  assert.deepEqual(f.calls.at(-1)!.body, selection('S01', '2026-01-30'))
  assert.equal(isDecisionBusy(f.state()), true)
  f.calls.at(-1)!.pending.resolve(job())
  await starting
  assert.equal(f.state().analysisPhase, 'polling')
  assert.equal(f.timers.size, 1)
  f.session.dispose()
})

test('polls at 1.8 seconds and stops permanently after a successful terminal result', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  f.calls.at(-1)!.pending.resolve(job())
  await starting
  f.nextTimer()
  assert.equal(f.calls.at(-1)!.path, '/api/ai/analyses/job-fixture')
  f.calls.at(-1)!.pending.resolve(job('running'))
  await tick()
  assert.equal(f.timers.size, 1)
  f.nextTimer()
  f.calls.at(-1)!.pending.resolve(job('succeeded'))
  await tick()
  assert.equal(f.state().analysisPhase, 'succeeded')
  assert.equal(f.state().job?.result?.summary, '综合建议')
  assert.equal([...f.timers.values()].filter(timer => timer.delay === 1800).length, 0)
  assert.equal(f.state().savedReports[0].id, 'S01_20260130_2001')
  assert.equal(f.state().job?.id, 'job-fixture')
  f.session.dispose()
})

test('failed jobs stop polling and require another explicit manual start', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  f.calls.at(-1)!.pending.resolve(job('failed'))
  await starting
  assert.equal(f.state().analysisError, '分析暂不可用')
  assert.equal(f.state().analysisPhase, 'failed')
  assert.equal(f.timers.size, 0)
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
  const retry = f.session.start()
  assert.equal(f.state().analysisError, null)
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 2)
  f.calls.at(-1)!.pending.resolve(job('succeeded', 'retry-fixture'))
  await retry
  f.session.dispose()
})

test('switching stations aborts polling and ignores an already pending old result', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  f.calls.at(-1)!.pending.resolve(job())
  await starting
  f.nextTimer()
  const oldPoll = f.calls.at(-1)!
  const loading = f.session.load(selection('S02'))
  assert.equal(oldPoll.signal.aborted, true)
  assert.equal(f.state().job, null)
  assert.equal(f.state().evidence, null)
  f.calls.at(-1)!.pending.resolve(range('S02'))
  await tick()
  f.calls.at(-1)!.pending.resolve(evidence('S02'))
  await loading
  oldPoll.pending.resolve(job('succeeded'))
  await tick()
  assert.equal(f.state().selection.stationId, 'S02')
  assert.equal(f.state().job, null)
  assert.equal(f.state().analysisPhase, 'idle')
  assert.equal(f.timers.size, 0)
  f.session.dispose()
})

test('date changes clear prior evidence and late errors cannot overwrite the new selection', async () => {
  const f = fixture()
  await f.ready()
  const first = f.session.load(selection('S01', '2026-01-29'))
  const old = f.calls.at(-1)!
  const next = f.session.load(selection('S01', '2026-01-28'))
  assert.equal(old.signal.aborted, true)
  assert.equal(f.state().evidence, null)
  f.calls.at(-1)!.pending.resolve(evidence('S01', '2026-01-28'))
  await next
  old.pending.reject(new Error('old error'))
  await first
  assert.equal(f.state().selection.date, '2026-01-28')
  assert.equal(f.state().dataError, null)
  f.session.dispose()
})

test('switching during submission prevents late accepted jobs from scheduling polls', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  const old = f.calls.at(-1)!
  const loading = f.session.load({ ...selection('S01', '2026-01-30'), growthStage: 'tillering' })
  f.calls.at(-1)!.pending.resolve({ ...evidence(), growthStage: 'tillering', growthStageLabel: '分蘖期', growthStageSource: 'user' })
  await loading
  old.pending.resolve(job())
  await starting
  assert.equal(old.signal.aborted, true)
  assert.equal(f.timers.size, 0)
  assert.equal(f.state().job, null)
  assert.equal(f.state().selection.growthStage, 'tillering')
  f.session.dispose()
})

test('unmount clears scheduled polls and suppresses all late publications', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  f.calls.at(-1)!.pending.resolve(job())
  await starting
  const states = f.states.length
  const calls = f.calls.length
  f.session.dispose()
  assert.equal(f.timers.size, 0)
  assert.equal(f.calls.at(-1)!.signal.aborted, true)
  await f.session.start()
  await f.session.load(selection('S02'))
  await f.session.refreshStatus()
  assert.equal(f.states.length, states)
  assert.equal(f.calls.length, calls)
})

test('unconfigured service and empty observations keep analysis disabled without blocking evidence', async () => {
  const unconfigured = fixture()
  await unconfigured.ready(false)
  assert.ok(unconfigured.state().evidence)
  await unconfigured.session.start()
  assert.equal(unconfigured.calls.some(call => call.method === 'POST'), false)
  unconfigured.session.dispose()
  const empty = fixture()
  await empty.ready(true, { ...evidence(), observedDays: 0, rawRowCount: 0 })
  assert.equal(canStartDecision(empty.state()), false)
  await empty.session.start()
  assert.equal(empty.calls.some(call => call.method === 'POST'), false)
  empty.session.dispose()
})

test('a poll network error permits manual retry without automatic POST or further polling', async () => {
  const f = fixture()
  await f.ready()
  const starting = f.session.start()
  f.calls.at(-1)!.pending.resolve(job())
  await starting
  f.nextTimer()
  f.calls.at(-1)!.pending.reject(new Error('network unavailable'))
  await tick()
  assert.equal(f.state().analysisPhase, 'failed')
  assert.equal(f.state().analysisError, 'network unavailable')
  assert.equal(canStartDecision(f.state()), true)
  assert.equal(f.timers.size, 0)
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
  f.session.dispose()
})

test('mismatched evidence and job identity never display unrelated results', async () => {
  const f = fixture()
  await f.ready(true, evidence('S02'))
  assert.equal(f.state().evidence, null)
  assert.match(f.state().dataError!, /不一致/)
  f.session.dispose()
  const next = fixture()
  await next.ready()
  const starting = next.session.start()
  next.calls.at(-1)!.pending.resolve({ ...job('succeeded'), stationId: 'S02' })
  await starting
  assert.equal(next.state().job, null)
  assert.equal(next.state().analysisPhase, 'failed')
  assert.equal(next.timers.size, 0)
  next.session.dispose()
})

test('legacy data remains explicitly dated and unavailable baselines remain empty', async () => {
  const f = fixture()
  const observed = evidence()
  observed.legacyContext = { source: 'inspection_and_hive_yield', stationId: 'S01',
    pestDisease: { available: false, source: 'none', sourceTable: '', stationId: 'S01', referenceDate: '2026-01-30',
      lastDiagnosedAt: null, recognitionCount: 0, items: [], notes: ['该日期不是当天'] },
    yield: { available: false, sourceTable: 'rice_yield', referenceYear: null, season: '', matchType: 'missing', baselineKgPerMu: null },
    limitations: ['所选年份暂无可用产量基线'],
  }
  await f.ready(true, observed)
  assert.equal(f.state().evidence?.legacyContext?.pestDisease.referenceDate, '2026-01-30')
  assert.equal(f.state().evidence?.legacyContext?.yield.baselineKgPerMu, null)
  assert.equal(canStartDecision(f.state()), true)
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  f.session.dispose()
})

test('formatting preserves actual zero and signed changes without inventing missing numbers', () => {
  assert.equal(formatEvidenceNumber(0), '0')
  assert.equal(formatEvidenceNumber(1.25, true), '+1.25')
  assert.equal(formatEvidenceNumber(-2.5, true), '-2.5')
  for (const value of [null, undefined, NaN, Infinity]) assert.equal(formatEvidenceNumber(value), '—')
})

function archive(id = 'S01_20260130_2001'): AiAnalysisJob {
  return { ...job('succeeded', id), archiveId: id }
}

test('saved reports are listed by station, and opening an earlier report adopts its analysis context using GET only', async () => {
  const previous = archive()
  previous.date = '2020-02-14'
  previous.windowDays = 14
  previous.result!.evidence = { ...evidence(), startDate: '2020-02-01', endDate: previous.date, windowDays: 14,
    growthStage: 'tillering', growthStageLabel: '分蘖期', growthStageSource: 'user' }
  const f = fixture({ reports: [{ ...previous, result: null }] })
  await f.ready()
  const listCall = f.calls.find(call => call.path.startsWith('/api/ai/analyses?'))!
  assert.equal(listCall.path, '/api/ai/analyses?stationId=S01')
  assert.equal(f.state().job, null) // A list never automatically selects a report.
  const viewing = f.session.viewSavedReport(previous.id)
  assert.equal(f.calls.at(-1)!.path, '/api/ai/analyses/S01_20260130_2001')
  assert.equal(canStartDecision(f.state()), false)
  f.calls.at(-1)!.pending.resolve(previous)
  await viewing
  assert.deepEqual(f.state().selection, { stationId: 'S01', date: '2020-02-14', windowDays: 14, growthStage: 'tillering' })
  assert.equal(f.state().evidence?.endDate, '2020-02-14')
  assert.equal(f.state().viewingSavedReport, true)
  assert.equal(f.state().job?.result?.summary, '综合建议')
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  f.session.dispose()
})

test('generated-date filters use Shanghai calendar days rather than analysis dates and stale lists are ignored', async () => {
  const f = fixture({ automaticLists: false })
  await f.ready()
  const original = f.calls.find(call => call.path.startsWith('/api/ai/analyses?'))!
  const loading = f.session.refreshReports('2026-01-31')
  assert.equal(original.signal.aborted, true)
  assert.equal(f.calls.at(-1)!.path, '/api/ai/analyses?stationId=S01&generatedDate=2026-01-31')
  const saved = { ...archive(), generatedAt: '2026-01-30T16:01:00Z', expiresAt: '2026-01-30T17:01:00Z', result: null }
  f.calls.at(-1)!.pending.resolve([saved])
  await loading
  original.pending.resolve([archive('stale')])
  await tick()
  assert.equal(f.state().savedReports.length, 1)
  assert.equal(f.state().savedReports[0].generatedAt, '2026-01-30T16:01:00Z')
  assert.equal(f.state().reportsGeneratedDate, '2026-01-31')
  assert.equal(reportGeneratedDate(saved), '2026-01-31')
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  f.session.dispose()
})

test('a reconstructed page can list and retrieve persisted reports without starting another model run', async () => {
  const first = fixture()
  await first.ready()
  const starting = first.session.start()
  first.calls.at(-1)!.pending.resolve(job('succeeded'))
  await starting
  const metadata = first.state().savedReports[0]
  first.session.dispose()
  const restored = fixture({ reports: [metadata] })
  await restored.ready()
  assert.equal(restored.state().job, null)
  const viewing = restored.session.viewSavedReport(metadata.id)
  restored.calls.at(-1)!.pending.resolve(archive(metadata.id))
  await viewing
  assert.equal(restored.state().job?.result?.summary, '综合建议')
  assert.equal(restored.calls.some(call => call.method === 'POST'), false)
  restored.session.dispose()
})

test('switching filters during a saved report request aborts it and preserves the new selection', async () => {
  const f = fixture({ reports: [{ ...archive(), result: null }] })
  await f.ready()
  const viewing = f.session.viewSavedReport('S01_20260130_2001')
  const old = f.calls.at(-1)!
  const loading = f.session.load(selection('S01', '2026-01-29'))
  f.calls.at(-1)!.pending.resolve(evidence('S01', '2026-01-29'))
  await loading
  old.pending.resolve(archive())
  await viewing
  assert.equal(old.signal.aborted, true)
  assert.equal(f.state().selection.date, '2026-01-29')
  assert.equal(f.state().job, null)
  assert.equal(f.state().viewingSavedReport, false)
  f.session.dispose()
})

test('selecting a second saved report ignores a late first response, even while evidence was loading', async () => {
  const first = archive('S01_20260130_2001')
  const second = archive('S01_20260130_2002')
  second.result!.summary = '第二份报告'
  const f = fixture({ reports: [{ ...first, result: null }, { ...second, result: null }] })
  const initialLoad = f.session.load(selection())
  const pendingRange = f.calls.at(-1)!
  await tick()
  const loadingFirst = f.session.viewSavedReport(first.id)
  const firstCall = f.calls.at(-1)!
  assert.equal(pendingRange.signal.aborted, true)
  const loadingSecond = f.session.viewSavedReport(second.id)
  f.calls.at(-1)!.pending.resolve(second)
  await loadingSecond
  firstCall.pending.resolve(first)
  pendingRange.pending.resolve(range())
  await Promise.all([loadingFirst, initialLoad])
  assert.equal(firstCall.signal.aborted, true)
  assert.equal(f.state().job?.id, second.id)
  assert.equal(f.state().job?.result?.summary, '第二份报告')
  assert.equal(f.calls.some(call => call.path.startsWith('/api/ai/evidence')), false)
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  f.session.dispose()
})

test('reports expire only after their deadline and client clock checks update both report and list without network requests', async () => {
  const f = fixture({ reports: [{ ...archive(), result: null }] })
  await f.ready()
  const viewing = f.session.viewSavedReport('S01_20260130_2001')
  f.calls.at(-1)!.pending.resolve(archive())
  await viewing
  const deadline = Date.parse(f.state().job!.expiresAt!)
  assert.equal(isReportExpired(f.state().job, deadline), false)
  assert.equal(isReportExpired(f.state().job, deadline + 1), true)
  const count = f.calls.length
  const runClock = () => {
    const [id, timer] = f.timers.entries().next().value!
    f.timers.delete(id)
    timer.callback()
  }
  f.setTime(deadline)
  runClock()
  assert.equal(f.state().job!.expired, false)
  assert.equal([...f.timers.values()][0].delay, 1)
  f.setTime(deadline + 1)
  runClock()
  assert.equal(f.state().job!.expired, true)
  assert.equal(f.state().savedReports[0].expired, true)
  assert.equal(f.timers.size, 0)
  assert.equal(f.calls.length, count)
  assert.equal(canStartDecision(f.state()), true) // Re-generation is available, never automatic.
  f.session.dispose()
})

test('clock jumps are evaluated using absolute expiry timestamps and disposing clears expiry checks', async () => {
  const f = fixture({ reports: [{ ...archive(), result: null }] })
  await f.ready()
  assert.equal(f.timers.size, 1)
  const [id, timer] = f.timers.entries().next().value!
  f.timers.delete(id)
  f.setTime(Date.parse('2026-01-30T15:00:00Z'))
  timer.callback()
  assert.equal(f.state().savedReports[0].expired, true)
  assert.equal(f.calls.some(call => call.method === 'POST'), false)
  const future = { ...archive('S01_20260130_2300'), generatedAt: '2026-01-30T15:00:00Z', expiresAt: '2026-01-30T16:00:00Z', result: null }
  const refresh = f.session.refreshReports()
  // The fixture auto-resolves lists; replace this with a new session for an unexpired timer.
  await refresh
  f.session.dispose()
  const active = fixture({ reports: [future] })
  await active.ready()
  assert.equal(active.timers.size, 1)
  active.session.dispose()
  assert.equal(active.timers.size, 0)
})

test('report timestamps display explicit Beijing time and invalid times remain empty', () => {
  assert.match(formatReportTime('2026-01-30T16:01:02Z'), /2026[\/-]01[\/-]31.*00:01:02/)
  assert.equal(formatReportTime(null), '—')
  assert.equal(formatReportTime('invalid'), '—')
  assert.equal(isReportExpired(job('running'), Date.now()), false)
})
