import type { AiAnalysisJob, AiAnalysisListResponse, AiAnalysisRequest, AiEvidence, AiGrowthStage, AiStatusResponse, HistoryRangeResponse } from '@smart-rice-security/shared'

export type DecisionState = {
  selection: AiAnalysisRequest
  configured: boolean | null
  statusError: string | null
  range: HistoryRangeResponse | null
  evidence: AiEvidence | null
  loading: boolean
  dataError: string | null
  analysisPhase: 'idle' | 'submitting' | 'loading_report' | 'polling' | 'succeeded' | 'failed'
  job: AiAnalysisJob | null
  analysisError: string | null
  savedReports: AiAnalysisJob[]
  reportsLoading: boolean
  reportsError: string | null
  reportsGeneratedDate: string
  viewingSavedReport: boolean
}

type DecisionRequest = <T>(path: string, options?: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal }) => Promise<T>
export type DecisionScheduler = { schedule: (callback: () => void, delayMs: number) => unknown; cancel: (handle: unknown) => void }
const GROWTH_STAGES = ['unknown', 'seedling', 'tillering', 'jointing', 'booting', 'heading', 'filling', 'mature']

export function initialDecisionState(): DecisionState {
  return {
    selection: { stationId: 'S01', date: '', windowDays: 7, growthStage: 'unknown' },
    configured: null, statusError: null, range: null, evidence: null, loading: true,
    dataError: null, analysisPhase: 'idle', job: null, analysisError: null,
    savedReports: [], reportsLoading: false, reportsError: null, reportsGeneratedDate: '', viewingSavedReport: false,
  }
}

export function formatEvidenceNumber(value: number | null | undefined, signed = false): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value)
  return signed && value > 0 ? `+${number}` : number
}

export function formatReportTime(value: string | null | undefined): string {
  if (!value || !Number.isFinite(Date.parse(value))) return '—'
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function reportGeneratedDate(job: AiAnalysisJob): string {
  if (!job.generatedAt || !Number.isFinite(Date.parse(job.generatedAt))) return ''
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(job.generatedAt))
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-')
}

export function isReportExpired(job: AiAnalysisJob | null, now = Date.now()): boolean {
  return !!job && job.status === 'succeeded' && (job.expired === true ||
    (!!job.expiresAt && Number.isFinite(Date.parse(job.expiresAt)) && now > Date.parse(job.expiresAt)))
}

export function isDecisionBusy(state: DecisionState): boolean {
  return state.analysisPhase === 'submitting' || state.analysisPhase === 'loading_report' || state.analysisPhase === 'polling'
}

export function canStartDecision(state: DecisionState): boolean {
  return state.configured === true && !state.loading && !state.dataError && !isDecisionBusy(state) &&
    !!state.evidence && state.evidence.observedDays > 0 && validDate(state.selection.date)
}

function validDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function matchesEvidence(evidence: AiEvidence | null, selection: AiAnalysisRequest): evidence is AiEvidence {
  return !!evidence && evidence.stationId === selection.stationId && evidence.endDate === selection.date &&
    evidence.windowDays === selection.windowDays && evidence.growthStage === selection.growthStage &&
    validDate(evidence.startDate) && Array.isArray(evidence.metrics) && Array.isArray(evidence.daily) &&
    Array.isArray(evidence.missingDates) && Array.isArray(evidence.limitations)
}

function matchesJob(job: AiAnalysisJob, selection: AiAnalysisRequest): boolean {
  return !!job && typeof job.id === 'string' && !!job.id && job.stationId === selection.stationId &&
    job.date === selection.date && job.windowDays === selection.windowDays &&
    ['queued', 'running', 'succeeded', 'failed'].includes(job.status)
}

function validResult(job: AiAnalysisJob, selection: AiAnalysisRequest): boolean {
  return !!job.result && matchesEvidence(job.result.evidence, selection) &&
    ['weatherAnalysis', 'soilAnalysis', 'riskAnalysis', 'summary'].every(key =>
      typeof job.result?.[key as keyof typeof job.result] === 'string')
}

function selectionQuery(selection: AiAnalysisRequest): string {
  return new URLSearchParams({ stationId: selection.stationId, date: selection.date,
    windowDays: String(selection.windowDays), growthStage: selection.growthStage }).toString()
}

/** Reading evidence, archived reports and their expiry never submits a model request. */
export function createDecisionSession(
  request: DecisionRequest,
  onChange: (state: DecisionState) => void,
  describeError: (error: unknown) => string,
  scheduler: DecisionScheduler = {
    schedule: (callback, delay) => setTimeout(callback, delay),
    cancel: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
  },
  now: () => number = Date.now,
) {
  let state = initialDecisionState()
  let disposed = false
  let selectionVersion = 0
  let analysisVersion = 0
  let statusVersion = 0
  let reportsVersion = 0
  let dataController: AbortController | null = null
  let analysisController: AbortController | null = null
  let statusController: AbortController | null = null
  let reportsController: AbortController | null = null
  let pollTimer: unknown = null
  let expiryTimer: unknown = null
  let cachedRange: HistoryRangeResponse | null = null

  const publish = (patch: Partial<DecisionState>) => {
    if (disposed) return
    state = { ...state, ...patch }
    onChange(state)
  }
  const cancelAnalysis = () => {
    ++analysisVersion
    analysisController?.abort()
    if (pollTimer !== null) scheduler.cancel(pollTimer)
    pollTimer = null
  }
  const cancelExpiry = () => {
    if (expiryTimer !== null) scheduler.cancel(expiryTimer)
    expiryTimer = null
  }
  const withExpiry = (job: AiAnalysisJob): AiAnalysisJob => ({ ...job, expired: isReportExpired(job, now()) })
  const scheduleExpiry = () => {
    cancelExpiry()
    if (disposed) return
    const jobs = [...state.savedReports, ...(state.job ? [state.job] : [])]
    const deadlines = jobs.filter(job => job.status === 'succeeded' && !job.expired && job.expiresAt)
      .map(job => Date.parse(job.expiresAt!)).filter(Number.isFinite)
    if (!deadlines.length) return
    const delay = Math.max(1, Math.min(60_000, Math.min(...deadlines) - now() + 1))
    expiryTimer = scheduler.schedule(() => {
      expiryTimer = null
      if (disposed) return
      const job = state.job ? withExpiry(state.job) : null
      const reports = state.savedReports.map(withExpiry)
      if (job?.expired !== state.job?.expired || reports.some((report, index) => report.expired !== state.savedReports[index].expired)) {
        publish({ job, savedReports: reports })
      }
      scheduleExpiry()
    }, delay)
  }

  const refreshReports = async (generatedDate = state.reportsGeneratedDate) => {
    if (disposed || state.analysisPhase === 'submitting' || state.analysisPhase === 'polling') return
    const version = ++reportsVersion
    const stationId = state.selection.stationId
    reportsController?.abort()
    const controller = new AbortController()
    reportsController = controller
    const current = () => !disposed && version === reportsVersion && state.selection.stationId === stationId && !controller.signal.aborted
    publish({ reportsGeneratedDate: generatedDate, reportsLoading: true, reportsError: null, savedReports: [] })
    scheduleExpiry()
    try {
      if (generatedDate && !validDate(generatedDate)) throw new Error('请选择有效的报告生成日期')
      const query = new URLSearchParams({ stationId })
      if (generatedDate) query.set('generatedDate', generatedDate)
      const reports = await request<AiAnalysisListResponse>(`/api/ai/analyses?${query}`, { signal: controller.signal })
      if (!current()) return
      if (!Array.isArray(reports) || reports.some(report => !report || typeof report.id !== 'string' || !report.id ||
          report.stationId !== stationId || report.status !== 'succeeded' || !validDate(report.date) ||
          ![7, 14, 30].includes(report.windowDays) || !reportGeneratedDate(report) ||
          (generatedDate && reportGeneratedDate(report) !== generatedDate))) {
        throw new Error('已保存报告列表与所选站点或生成日期不一致，请重新读取')
      }
      publish({ savedReports: reports.slice(0, 20).map(report => withExpiry({ ...report, result: null })), reportsLoading: false })
      scheduleExpiry()
    } catch (error) {
      if (current()) publish({ savedReports: [], reportsLoading: false, reportsError: describeError(error) })
    }
  }

  const remember = (job: AiAnalysisJob) => {
    if (!job.archiveId || (state.reportsGeneratedDate && reportGeneratedDate(job) !== state.reportsGeneratedDate)) return
    const archived = withExpiry({ ...job, id: job.archiveId, result: null })
    publish({ savedReports: [archived, ...state.savedReports.filter(report => report.id !== archived.id)]
      .sort((left, right) => (right.generatedAt ?? '').localeCompare(left.generatedAt ?? '')).slice(0, 20) })
  }

  return {
    async refreshStatus() {
      if (disposed) return
      const version = ++statusVersion
      statusController?.abort()
      const controller = new AbortController()
      statusController = controller
      publish({ configured: null, statusError: null })
      try {
        const response = await request<AiStatusResponse>('/api/ai/status', { signal: controller.signal })
        if (disposed || version !== statusVersion || controller.signal.aborted) return
        if (typeof response?.configured !== 'boolean') throw new Error('暂时无法确认分析服务状态')
        publish({ configured: response.configured })
      } catch (error) {
        if (!disposed && version === statusVersion && !controller.signal.aborted) publish({ configured: null, statusError: describeError(error) })
      }
    },

    async load(selection: AiAnalysisRequest, refreshRange = false) {
      if (disposed) return
      const version = ++selectionVersion
      dataController?.abort()
      ++reportsVersion
      reportsController?.abort()
      cancelAnalysis()
      cancelExpiry()
      const controller = new AbortController()
      dataController = controller
      const current = () => !disposed && version === selectionVersion && !controller.signal.aborted
      if (refreshRange || cachedRange?.stationId !== selection.stationId) cachedRange = null
      let selected = { ...selection }
      let range = cachedRange
      publish({ selection: selected, range, evidence: null, loading: true, dataError: null,
        job: null, analysisPhase: 'idle', analysisError: null, savedReports: [], reportsLoading: false,
        reportsError: null, viewingSavedReport: false })
      void refreshReports()
      try {
        if (!/^S(?:0[1-9]|10)$/.test(selected.stationId)) throw new Error('请选择有效监测站点')
        if (selected.date && !validDate(selected.date)) throw new Error('请选择有效日期')
        if (!range) {
          range = await request<HistoryRangeResponse>(`/api/history/range?stationId=${encodeURIComponent(selected.stationId)}`, { signal: controller.signal })
          if (!current()) return
          if (range?.recordCount === 0) throw new Error('该站点暂无历史监测数据')
          if (!range || range.stationId !== selected.stationId || !validDate(range.startDate) ||
              !validDate(range.endDate) || range.startDate > range.endDate) throw new Error('历史数据范围无效，请重新读取')
          cachedRange = range
        }
        const date = !selected.date || selected.date > range.endDate ? range.endDate
          : selected.date < range.startDate ? range.startDate : selected.date
        selected = { ...selected, date }
        publish({ selection: selected, range })
        const evidence = await request<AiEvidence>(`/api/ai/evidence?${selectionQuery(selected)}`, { signal: controller.signal })
        if (!current()) return
        if (!matchesEvidence(evidence, selected)) throw new Error('监测证据与当前选择不一致，请重新读取')
        publish({ evidence, loading: false })
      } catch (error) {
        if (current()) publish({ evidence: null, loading: false, dataError: describeError(error) })
      }
    },

    async start() {
      if (disposed || !canStartDecision(state)) return
      cancelAnalysis()
      ++reportsVersion
      reportsController?.abort()
      const version = analysisVersion
      const selectedVersion = selectionVersion
      const selection = { ...state.selection }
      const controller = new AbortController()
      analysisController = controller
      const current = () => !disposed && version === analysisVersion && selectedVersion === selectionVersion && !controller.signal.aborted
      publish({ job: null, analysisPhase: 'submitting', analysisError: null, viewingSavedReport: false, reportsLoading: false })
      scheduleExpiry()
      const fail = (error: unknown) => {
        if (current()) publish({ analysisPhase: 'failed', analysisError: describeError(error) })
      }
      const accept = (received: AiAnalysisJob, expectedId?: string) => {
        if (!current()) return
        if (!matchesJob(received, selection) || (expectedId && received.id !== expectedId)) throw new Error('分析结果与当前请求不一致，请重新读取')
        const job = withExpiry(received)
        if (job.status === 'succeeded') {
          if (!validResult(job, selection)) throw new Error('报告内容或生育期与当前选择不一致，请重新读取')
          remember(job)
          publish({ job, evidence: job.result!.evidence, analysisPhase: 'succeeded', analysisError: null })
          scheduleExpiry()
        } else if (job.status === 'failed') {
          publish({ job, analysisPhase: 'failed', analysisError: job.error || '本次分析未完成，请手动重试' })
        } else {
          publish({ job, analysisPhase: 'polling' })
          pollTimer = scheduler.schedule(() => {
            pollTimer = null
            if (!current()) return
            void request<AiAnalysisJob>(`/api/ai/analyses/${encodeURIComponent(job.id)}`, { signal: controller.signal })
              .then(next => accept(next, job.id)).catch(fail)
          }, 1800)
        }
      }
      try {
        const job = await request<AiAnalysisJob>('/api/ai/analyses', { method: 'POST', body: selection, signal: controller.signal })
        accept(job)
      } catch (error) { fail(error) }
    },

    refreshReports,

    async viewSavedReport(id: string) {
      if (disposed || state.analysisPhase === 'submitting' || state.analysisPhase === 'polling') return
      const listed = state.savedReports.find(report => report.id === id)
      if (!listed) return
      cancelAnalysis()
      ++selectionVersion
      dataController?.abort()
      const version = analysisVersion
      const selectedVersion = selectionVersion
      const controller = new AbortController()
      analysisController = controller
      const current = () => !disposed && version === analysisVersion && selectedVersion === selectionVersion && !controller.signal.aborted
      publish({ job: null, loading: false, dataError: null, analysisPhase: 'loading_report', analysisError: null, viewingSavedReport: true })
      scheduleExpiry()
      try {
        const received = await request<AiAnalysisJob>(`/api/ai/analyses/${encodeURIComponent(id)}`, { signal: controller.signal })
        if (!current()) return
        const stage = received?.result?.evidence?.growthStage
        if (!received || received.id !== id || received.stationId !== listed.stationId || received.status !== 'succeeded' ||
            !validDate(received.date) || ![7, 14, 30].includes(received.windowDays) || !stage || !GROWTH_STAGES.includes(stage)) {
          throw new Error('已保存报告内容不完整或与所选记录不一致，请重新读取')
        }
        const selection: AiAnalysisRequest = { stationId: received.stationId, date: received.date,
          windowDays: received.windowDays, growthStage: stage as AiGrowthStage }
        if (!validResult(received, selection)) throw new Error('已保存报告与其证据不一致，请重新读取')
        const job = withExpiry(received)
        publish({ selection, range: cachedRange?.stationId === selection.stationId ? cachedRange : null,
          evidence: job.result!.evidence, job, analysisPhase: 'succeeded', analysisError: null })
        remember(job)
        scheduleExpiry()
      } catch (error) {
        if (current()) publish({ analysisPhase: 'failed', analysisError: describeError(error) })
      }
    },

    dispose() {
      disposed = true
      ++selectionVersion
      ++statusVersion
      ++reportsVersion
      dataController?.abort()
      statusController?.abort()
      reportsController?.abort()
      cancelAnalysis()
      cancelExpiry()
    },
  }
}
