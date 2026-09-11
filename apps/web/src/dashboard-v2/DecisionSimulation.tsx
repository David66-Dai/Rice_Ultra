import { useEffect, useRef, useState } from 'react'
import type { AiAnalysisRequest, AiGrowthStage, AiWindowDays } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import { canStartDecision, createDecisionSession, formatEvidenceNumber, formatReportTime, initialDecisionState, isDecisionBusy, isReportExpired } from '../lib/decision.ts'
import './DecisionSimulation.css'

const STATIONS = Array.from({ length: 10 }, (_, index) => `S${String(index + 1).padStart(2, '0')}`)
const STAGES: { value: AiGrowthStage; label: string }[] = [
  { value: 'unknown', label: '未知 / 未提供' }, { value: 'seedling', label: '育秧期' },
  { value: 'tillering', label: '分蘖期' }, { value: 'jointing', label: '拔节期' },
  { value: 'booting', label: '孕穗期' }, { value: 'heading', label: '抽穗期' },
  { value: 'filling', label: '灌浆期' }, { value: 'mature', label: '成熟期' },
]

function AnalysisText({ text }: { text: string }) {
  const inline = (value: string) => value.split(/(\*\*[^*\n]+\*\*)/g).map((part, index) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={index}>{part.slice(2, -2)}</strong> : part)
  return <div className="decision-analysis-text">{text.split(/\n\s*\n/).map((block, index) => {
    const content = block.trim()
    if (/^#{1,6}\s+[^\n]+$/.test(content)) return <h5 key={index}>{inline(content.replace(/^#{1,6}\s+/, ''))}</h5>
    if (content.split('\n').every(line => /^[-*]\s+/.test(line))) return <ul key={index}>{content.split('\n').map((line, lineIndex) =>
      <li key={lineIndex}>{inline(line.replace(/^[-*]\s+/, ''))}</li>)}</ul>
    return <p key={index}>{inline(content)}</p>
  })}</div>
}

export function DecisionSimulation() {
  const auth = useAuth()
  const [state, setState] = useState(initialDecisionState)
  const session = useRef<ReturnType<typeof createDecisionSession> | null>(null)
  const request = useRef(auth.request)
  request.current = auth.request

  useEffect(() => {
    const current = createDecisionSession((path, options) => request.current(path, options), setState, describeError)
    session.current = current
    void current.refreshStatus()
    void current.load(initialDecisionState().selection)
    return () => {
      current.dispose()
      session.current = null
    }
  }, [])

  const { selection, range, job } = state
  const result = state.analysisPhase === 'succeeded' ? job?.result : null
  const evidence = state.viewingSavedReport && result ? result.evidence : state.evidence
  const legacy = evidence?.legacyContext
  const busy = isDecisionBusy(state)
  const expired = isReportExpired(job)
  const select = (patch: Partial<AiAnalysisRequest>) => {
    void session.current?.load({ ...selection, ...patch })
  }
  const stageLabel = STAGES.find(stage => stage.value === selection.growthStage)?.label ?? '未知 / 未提供'
  const statusLabel = state.analysisPhase === 'submitting' ? '正在提交'
    : state.analysisPhase === 'loading_report' ? '正在读取报告'
    : state.analysisPhase === 'polling' ? job?.status === 'queued' ? '排队中' : '分析中'
    : state.analysisPhase === 'succeeded' ? expired ? '已过期' : '分析完成'
    : state.analysisPhase === 'failed' ? '分析未完成' : '等待开始'
  const analyses = [
    { key: 'weather', number: '01', title: '气象环境分析', subtitle: 'ENVIRONMENT', text: result?.weatherAnalysis },
    { key: 'soil', number: '02', title: '土壤养分分析', subtitle: 'SOIL & NUTRIENTS', text: result?.soilAnalysis },
    { key: 'risk', number: '03', title: '风险分析', subtitle: 'RISK ASSESSMENT', text: result?.riskAnalysis },
    { key: 'summary', number: '04', title: '综合建议', subtitle: 'DECISION BRIEF', text: result?.summary },
  ]

  return (
    <div className="decision-page">
      <section className="decision-panel decision-control" aria-labelledby="decision-title">
        <header className="decision-head">
          <div>
            <span>FIELD INTELLIGENCE / EVIDENCE TO ACTION</span>
            <h2 id="decision-title">决策推演<span>基于历史监测的农业分析</span></h2>
          </div>
          <div className={`decision-service${state.configured === true ? ' is-ready' : ''}`} role="status">
            <i aria-hidden="true" />{state.configured === true ? '分析服务已配置' : state.configured === false ? '分析服务待配置' : state.statusError ? '服务状态暂不可用' : '正在确认服务状态'}
          </div>
        </header>

        <div className="decision-filters">
          <label className="decision-field"><span>监测站点</span>
            <select value={selection.stationId} onChange={event => select({ stationId: event.target.value, date: '' })}>
              {STATIONS.map((station, index) => <option key={station} value={station}>{station} · {index + 1} 号监测站</option>)}
            </select>
          </label>
          <label className="decision-field"><span>分析截止日期</span>
            <input type="date" value={selection.date} min={range?.startDate} max={range?.endDate}
              disabled={!range && !selection.date} onChange={event => select({ date: event.target.value })} />
          </label>
          <fieldset className="decision-window"><legend>观察窗口</legend>
            <div>{([7, 14, 30] as AiWindowDays[]).map(days => <button key={days} type="button"
              aria-pressed={selection.windowDays === days} onClick={() => select({ windowDays: days })}>{days}<small> 天</small></button>)}</div>
          </fieldset>
          <label className="decision-field"><span>生育期 <small>手动补充</small></span>
            <select value={selection.growthStage} onChange={event => select({ growthStage: event.target.value as AiGrowthStage })}>
              {STAGES.map(stage => <option key={stage.value} value={stage.value}>{stage.label}</option>)}
            </select>
          </label>
          <button type="button" className="decision-run" disabled={!canStartDecision(state)} onClick={() => void session.current?.start()}>
            <span aria-hidden="true">{busy ? '◌' : '↗'}</span>{busy ? statusLabel : expired ? '重新生成报告' : state.analysisPhase === 'failed' && !state.viewingSavedReport ? '手动重试分析' : result ? '重新分析' : '开始分析'}
          </button>
        </div>
        <div className="decision-filter-note">
          <span>{range ? `可用历史：${range.startDate} — ${range.endDate}` : state.loading ? '正在读取历史范围' : '暂无可用历史范围'}</span>
          <span>生育期由你提供；每次分析使用所选窗口的监测证据。</span>
        </div>
        {state.configured === false && <div className="decision-notice" role="status">
          <span>分析与报告归档服务尚未就绪，请联系管理员完成配置。监测证据仍可查看。</span>
          <button type="button" onClick={() => void session.current?.refreshStatus()}>重新检查</button>
        </div>}
        {state.statusError && <div className="decision-notice is-error" role="alert">
          <span>暂时无法确认分析服务状态：{state.statusError}</span>
          <button type="button" onClick={() => void session.current?.refreshStatus()}>重新检查</button>
        </div>}
      </section>

      {evidence && <section className="decision-legacy" aria-labelledby="decision-legacy-title">
        <header><h3 id="decision-legacy-title">病虫害 / 产量参考</h3></header>
        <div className="decision-legacy-grid">
          <article className="decision-panel decision-legacy-card">
            <header><h4>病虫害归档</h4><span>{legacy?.disease.available ? legacy.disease.referenceDate : '暂无可用归档'}</span></header>
            {legacy?.disease.available ? <>
              <div className="decision-legacy-values">{legacy.disease.values.map(metric => <div key={metric.field}>
                <span>{metric.label}</span><strong>{metric.value ?? '—'}<small>{metric.unit}</small></strong>
              </div>)}</div>
              <p>{legacy.disease.matchType === 'latest_prior' ? '采用目标日期之前最近一次病虫害记录。' : '采用目标日期的病虫害记录。'}</p>
            </> : <p>当前没有可用的旧项目病虫害参考，环境分析仍可继续。</p>}
          </article>
          <article className="decision-panel decision-legacy-card decision-legacy-card--yield">
            <header><h4>年度产量基线</h4><span>{legacy?.yield.available ? `${legacy.yield.referenceYear ?? '—'} 年 · ${legacy.yield.season === 'firstcrop' ? '第一季' : legacy.yield.season || '季节未标注'}` : '暂无可用归档'}</span></header>
            <div className="decision-legacy-yield"><strong>{legacy?.yield.available ? formatEvidenceNumber(legacy.yield.baselineKgPerMu) : '—'}</strong><span>kg/亩</span><small>旧项目产量参考</small></div>
            <p>{legacy?.yield.available ? legacy.yield.matchType === 'latest_prior' ? '采用目标年份之前最近一年的基线，供本次分析参考。' : '采用目标年份的基线，供本次分析参考。' : '当前没有可用产量基线，环境分析仍可继续。'}</p>
          </article>
        </div>
      </section>}

      <div className="decision-workbench">
        <section className="decision-panel decision-evidence" aria-labelledby="decision-evidence-title" aria-busy={state.loading}>
          <header className="decision-subhead">
            <div><span>OBSERVATION RECORD</span><h3 id="decision-evidence-title">{state.viewingSavedReport && result ? '报告证据快照' : '监测证据'}</h3></div>
            <button type="button" className="decision-text-button" disabled={state.loading || busy}
              onClick={() => void session.current?.load(selection, true)}>{state.loading ? '读取中…' : '刷新证据 ↻'}</button>
          </header>
          {state.loading ? <div className="decision-empty" role="status">
            <div className="decision-loader" aria-hidden="true" /><strong>正在读取 {selection.stationId} 的监测记录</strong><p>将按所选日期整理环境指标与缺测情况。</p>
          </div> : state.dataError ? <div className="decision-empty is-error" role="alert">
            <span className="decision-empty-symbol" aria-hidden="true">!</span><strong>监测证据读取失败</strong><p>{state.dataError}</p>
            <button type="button" className="decision-secondary" onClick={() => void session.current?.load(selection, true)}>重新读取</button>
          </div> : evidence ? <>
            <div className="decision-evidence-stats">
              <div><span>实测覆盖</span><strong>{evidence.observedDays}<small> / {evidence.windowDays} 天</small></strong></div>
              <div><span>原始记录</span><strong>{formatEvidenceNumber(evidence.rawRowCount)}<small> 条</small></strong></div>
              <div><span>缺测日期</span><strong className={evidence.missingDates.length ? 'is-gap' : ''}>{evidence.missingDates.length}<small> 天</small></strong></div>
            </div>
            <div className="decision-evidence-range"><strong>{evidence.stationId}</strong><span>{evidence.startDate} — {evidence.endDate}</span></div>
            {evidence.observedDays === 0 && <p className="decision-notice" role="status">所选窗口没有可用监测记录，请调整日期或观察窗口。</p>}
            <div className="decision-metrics-scroll" tabIndex={0} role="region" aria-label="环境指标证据表，可横向滚动">
              <table className="decision-metrics"><caption>环境指标日均统计 · 变化为目标日减窗口起始日</caption>
                <thead><tr><th scope="col">指标 / 单位</th><th scope="col">均值</th><th scope="col">变化</th><th scope="col">有效 / 缺测天数</th></tr></thead>
                <tbody>{evidence.metrics.map(metric => <tr key={metric.field}>
                  <th scope="row">{metric.label}<small>{metric.unit || '无量纲'}</small></th>
                  <td>{formatEvidenceNumber(metric.mean)}</td>
                  <td className="decision-metric-change">{formatEvidenceNumber(metric.change, true)}</td>
                  <td><span>{metric.count}</span><span className={metric.missingCount ? 'is-gap' : ''}> / {metric.missingCount}</span></td>
                </tr>)}</tbody>
              </table>
            </div>
            <div className="decision-evidence-foot">
              <p>生育期：<strong>{stageLabel}</strong>{selection.growthStage === 'unknown' ? ' · 未提供，不推定当前阶段' : ' · 用户提供'}</p>
              <p>“—”表示无有效数值；指标变化保留原单位。</p>
              {evidence.missingDates.length > 0 && <details><summary>查看缺测日期（{evidence.missingDates.length} 天）</summary><p>{evidence.missingDates.join('、')}</p></details>}
              {evidence.limitations.length > 0 && <details open><summary>数据说明</summary><ul>{evidence.limitations.map((text, index) => <li key={index}>{text}</li>)}</ul></details>}
            </div>
          </> : <div className="decision-empty"><strong>暂无监测证据</strong><p>选择监测站点和日期后读取。</p></div>}
        </section>

        <section className="decision-panel decision-analysis" aria-labelledby="decision-analysis-title" aria-busy={busy}>
          <header className="decision-subhead decision-analysis-head">
            <div><span>ANALYSIS BRIEF</span><h3 id="decision-analysis-title">分析结果</h3></div>
            <span className={`decision-job-status${busy ? ' is-running' : ''}${state.analysisPhase === 'failed' || expired ? ' is-failed' : ''}`} role="status"><i aria-hidden="true" />{statusLabel}</span>
          </header>
          <div className="decision-analysis-body">
          <details className="decision-saved-reports">
            <summary>查看已保存报告<span>{state.reportsLoading ? '读取中…' : `${state.savedReports.length} 条`}</span></summary>
            <div className="decision-saved-reports-body">
              <header><p>{selection.stationId} · 按报告生成时间查询，最多展示 20 条</p><button type="button" className="decision-text-button"
                disabled={state.reportsLoading || busy} onClick={() => void session.current?.refreshReports()}>刷新列表 ↻</button></header>
              <div className="decision-report-filters"><label>报告生成日期 <small>北京时间</small>
                <input type="date" value={state.reportsGeneratedDate} disabled={state.analysisPhase === 'submitting' || state.analysisPhase === 'polling'}
                  onChange={event => void session.current?.refreshReports(event.target.value)} /></label>
                <button type="button" className="decision-text-button" disabled={busy || !state.reportsGeneratedDate}
                  onClick={() => void session.current?.refreshReports('')}>查看最近报告</button></div>
              {state.reportsLoading ? <p className="decision-saved-empty" role="status">正在读取已保存报告…</p>
                : state.reportsError ? <p className="decision-saved-empty is-error" role="alert">{state.reportsError}</p>
                  : state.savedReports.length === 0 ? <p className="decision-saved-empty">当前条件下还没有已保存报告。</p>
                    : <ul>{state.savedReports.map(report => <li key={report.id}>
                      <div><time dateTime={report.generatedAt ?? undefined}>{formatReportTime(report.generatedAt)}</time>
                        <span className={`decision-saved-state${isReportExpired(report) ? ' is-expired' : ''}`}>{isReportExpired(report) ? '已过期' : '有效'}</span>
                        <small>分析截至 {report.date} · {report.windowDays} 天窗口</small></div>
                      <button type="button" disabled={state.analysisPhase === 'submitting' || state.analysisPhase === 'polling'} aria-pressed={state.viewingSavedReport && job?.id === report.id}
                        onClick={() => void session.current?.viewSavedReport(report.id)}>
                        {state.viewingSavedReport && job?.id === report.id ? '正在查看' : '查看报告'}
                      </button>
                    </li>)}</ul>}
            </div>
          </details>
          {state.viewingSavedReport && result && <p className="decision-report-note">正在查看已保存报告，左侧为报告生成时的证据快照。</p>}
          {result && <div className="decision-report-time"><span>生成时间（北京时间）：<time dateTime={job?.generatedAt ?? undefined}>{formatReportTime(job?.generatedAt)}</time></span>
            <span>有效至：{formatReportTime(job?.expiresAt)}</span></div>}
          {result && expired && <div className="decision-expired-notice" role="status"><div><strong>此报告已过期</strong>
            <p>报告生成已超过 1 小时，历史内容仍可查看。请重新生成报告后用于当前判断。</p></div>
            <button type="button" disabled={!canStartDecision(state)} onClick={() => void session.current?.start()}>重新生成报告</button></div>}
          {state.analysisError && <div className="decision-notice is-error" role="alert"><span>{state.analysisError}
            {state.viewingSavedReport
              ? ' 可在已保存报告列表中重新查看。' : ' 请检查后点击“手动重试分析”。'}</span></div>}
          <div className="decision-analysis-grid">
            {analyses.map(analysis => <article key={analysis.key} className={`decision-panel decision-analysis-card decision-analysis-card--${analysis.key}`}>
              <header><span className="decision-analysis-number">{analysis.number}</span><div><small>{analysis.subtitle}</small><h4>{analysis.title}</h4></div>
                {analysis.text && <span className="decision-complete-mark" aria-label="已生成">✓</span>}</header>
              {analysis.text ? <AnalysisText text={analysis.text} />
                : <div className="decision-analysis-placeholder"><span aria-hidden="true">{busy ? '◌' : '—'}</span>
                  <p>{state.analysisPhase === 'loading_report' ? '正在读取已保存的分析报告…' : busy ? '正在结合监测证据生成分析…' : state.analysisPhase === 'failed' ? '本次分析未完成' : '开始新分析，或查看已保存报告'}</p></div>}
            </article>)}
          </div>
          <footer className="decision-analysis-foot">
            {result && job?.generatedAt ? <span>生成时间（北京时间）：{formatReportTime(job.generatedAt)}</span>
              : <span>切换站点、日期或观察条件会清除当前结果。</span>}
            <span>分析建议供田间决策参考，请结合实地情况判断。</span>
          </footer>
          </div>
        </section>
      </div>
    </div>
  )
}
