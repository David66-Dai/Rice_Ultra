import { useState } from 'react'
import './DecisionSimulation.css'

type PlanId = 'A' | 'B'

type YieldPlan = {
  id: PlanId
  name: string
  subtitle: string
  finalYield: number
  recoveryYield: number
  increaseRate: number
  cost: number
  cycle: number
  risk: string
  confidence: number
  actions: string[]
  color: string
}

const BASE_YIELD = 528.4
const HISTORY_YIELD = 506.2

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
  online: index === 0,
}))

const PLANS: YieldPlan[] = [
  {
    id: 'A',
    name: '精准水肥协同方案',
    subtitle: '稳产优先 · 推荐方案',
    finalYield: 571.2,
    recoveryYield: 42.8,
    increaseRate: 8.1,
    cost: 86,
    cycle: 14,
    risk: '低风险',
    confidence: 91,
    actions: ['分阶段浅水灌溉', '补施穗粒肥 4.5kg/亩', '叶面喷施磷酸二氢钾'],
    color: '#4de0ff',
  },
  {
    id: 'B',
    name: '生物调控增产方案',
    subtitle: '低投入 · 快速恢复',
    finalYield: 558.5,
    recoveryYield: 30.1,
    increaseRate: 5.7,
    cost: 62,
    cycle: 10,
    risk: '中低风险',
    confidence: 84,
    actions: ['喷施生物刺激素', '增施微生物菌剂', '调整田间水层至 3–5cm'],
    color: '#69e3a9',
  },
]

const GROWTH_STAGES = [
  { name: '育秧期', done: true },
  { name: '分蘖期', done: true },
  { name: '拔节期', done: true },
  { name: '孕穗期', done: true },
  { name: '灌浆期', done: false, active: true },
  { name: '成熟期', done: false },
]

function YieldBars({ plan }: { plan: YieldPlan }) {
  const bars = [
    { label: '历史平均', value: HISTORY_YIELD, color: '#607f91' },
    { label: '当前预计', value: BASE_YIELD, color: '#4f9fc0' },
    { label: `方案 ${plan.id} 推演`, value: plan.finalYield, color: plan.color },
  ]

  return (
    <div className="yield-bars" aria-label={`方案${plan.id}产量推演对比`}>
      <div className="yield-bars__scale" aria-hidden="true">
        <span>600</span><span>450</span><span>300</span><span>150</span><span>0</span>
      </div>
      <div className="yield-bars__plot">
        {[25, 50, 75, 100].map((percent) => <i key={percent} style={{ bottom: `${percent}%` }} />)}
        {bars.map((bar) => (
          <div key={bar.label} className="yield-bar">
            <div className="yield-bar__value">
              <strong>{bar.value.toFixed(1)}</strong>
              <small>kg/亩</small>
            </div>
            <span
              style={{
                height: `${(bar.value / 600) * 100}%`,
                '--bar-color': bar.color,
              } as React.CSSProperties}
            />
            <em>{bar.label}</em>
          </div>
        ))}
      </div>
    </div>
  )
}

export function DecisionSimulation() {
  const [selectedPlanId, setSelectedPlanId] = useState<PlanId>('A')
  const [selectedStationId, setSelectedStationId] = useState('S01')
  const [stationMenuOpen, setStationMenuOpen] = useState(false)
  const selectedPlan = PLANS.find((plan) => plan.id === selectedPlanId) ?? PLANS[0]
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]

  return (
    <div className="decision-page">
      <section className="decision-overview decision-panel" aria-labelledby="decision-title">
        <header className="decision-head">
          <div>
            <span>AGRICULTURAL DECISION SIMULATION</span>
            <h2 id="decision-title">决策推演</h2>
          </div>
          <div className="decision-head__status">
            <div className="station-switch">
              <button
                type="button"
                className="station-switch__trigger"
                onClick={() => setStationMenuOpen((open) => !open)}
                aria-expanded={stationMenuOpen}
                aria-haspopup="listbox"
              >
                <span className={`station-switch__status${selectedStation.online ? '' : ' is-offline'}`}><i /></span>
                <span>
                  <small>当前站点</small>
                  <strong>{selectedStation.id} · {selectedStation.name}</strong>
                </span>
                <b aria-hidden="true">⌄</b>
              </button>

              {stationMenuOpen && (
                <div className="station-switch__menu" role="listbox" aria-label="选择推演站点">
                  {STATIONS.map((station) => (
                    <button
                      key={station.id}
                      type="button"
                      className={`${station.id === selectedStationId ? 'is-selected' : ''}${station.online ? '' : ' is-offline'}`}
                      onClick={() => {
                        setSelectedStationId(station.id)
                        setStationMenuOpen(false)
                      }}
                      role="option"
                      aria-selected={station.id === selectedStationId}
                    >
                      <i />
                      <span><strong>{station.id}</strong>{station.name}</span>
                      <em>{station.online ? '在线' : '离线'}</em>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <span className={selectedStation.online ? '' : 'is-offline'}>
              <i />{selectedStation.id} 模型{selectedStation.online ? '在线' : '离线'}
            </span>
          </div>
        </header>

        {selectedStation.online ? (
          <>
            <div className="decision-kpis">
          <article className="decision-kpi decision-kpi--yield">
            <div className="decision-kpi__icon" aria-hidden="true"><span>Y</span></div>
            <div>
              <small>EXPECTED YIELD</small>
              <h3>预计产量</h3>
              <strong>{BASE_YIELD.toFixed(1)}<em>kg/亩</em></strong>
              <p>较历史均值 <b>+4.4%</b></p>
            </div>
          </article>

          <article className="decision-kpi decision-kpi--stage">
            <div className="decision-kpi__icon" aria-hidden="true"><span>G</span></div>
            <div>
              <small>GROWTH STAGE</small>
              <h3>生长阶段</h3>
              <strong>灌浆期<em>进度 72%</em></strong>
              <p>预计距成熟期 <b>23 天</b></p>
            </div>
            <div className="stage-ring" style={{ '--stage-progress': '72%' } as React.CSSProperties}><span>72<small>%</small></span></div>
          </article>

          <article className="decision-kpi decision-kpi--recovery">
            <div className="decision-kpi__icon" aria-hidden="true"><span>R</span></div>
            <div>
              <small>RECOVERABLE YIELD</small>
              <h3>恢复产量</h3>
              <strong>+{selectedPlan.recoveryYield.toFixed(1)}<em>kg/亩</em></strong>
              <p>采用方案 {selectedPlan.id} 后预计恢复</p>
            </div>
          </article>
            </div>

            <div className="growth-timeline" aria-label="水稻生长阶段">
              {GROWTH_STAGES.map((stage, index) => (
                <div key={stage.name} className={`${stage.done ? 'is-done' : ''}${stage.active ? ' is-active' : ''}`}>
                  <span><i>{stage.done ? '✓' : index + 1}</i></span>
                  <strong>{stage.name}</strong>
                  {stage.active && <small>当前阶段</small>}
                </div>
              ))}
            </div>
          </>
        ) : (
          <div className="decision-offline" role="status">
            <i aria-hidden="true" />
            <strong>{selectedStation.name}当前离线</strong>
            <p>暂无预计产量、生长阶段、恢复产量及增产方案</p>
          </div>
        )}
      </section>

      {selectedStation.online && <div className="decision-main">
        <section className="yield-simulation decision-panel" aria-labelledby="yield-simulation-title">
          <header className="decision-subhead">
            <div>
              <span>YIELD SIMULATION</span>
              <h2 id="yield-simulation-title">产量推演结果</h2>
            </div>
            <strong>方案 {selectedPlan.id}</strong>
          </header>

          <YieldBars plan={selectedPlan} />

          <div className="simulation-result">
            <div>
              <small>推演后预计产量</small>
              <strong>{selectedPlan.finalYield.toFixed(1)}<span>kg/亩</span></strong>
            </div>
            <div>
              <small>预计增产幅度</small>
              <strong>+{selectedPlan.increaseRate.toFixed(1)}<span>%</span></strong>
            </div>
            <div>
              <small>模型置信度</small>
              <strong>{selectedPlan.confidence}<span>%</span></strong>
            </div>
          </div>
        </section>

        <section className="plan-section decision-panel" aria-labelledby="plan-title">
          <header className="decision-subhead">
            <div>
              <span>YIELD IMPROVEMENT PLANS</span>
              <h2 id="plan-title">增产方案选择</h2>
            </div>
            <small>点击方案查看推演结果</small>
          </header>

          <div className="plan-list">
            {PLANS.map((plan) => (
              <button
                key={plan.id}
                type="button"
                className={`plan-card${selectedPlanId === plan.id ? ' is-selected' : ''}`}
                style={{ '--plan-color': plan.color } as React.CSSProperties}
                onClick={() => setSelectedPlanId(plan.id)}
                aria-pressed={selectedPlanId === plan.id}
              >
                <div className="plan-card__flag">
                  <span>方案</span>
                  <strong>{plan.id}</strong>
                </div>
                <div className="plan-card__body">
                  <header>
                    <div>
                      <small>{plan.subtitle}</small>
                      <h3>{plan.name}</h3>
                    </div>
                    {plan.id === 'A' && <em>系统推荐</em>}
                  </header>

                  <div className="plan-card__metrics">
                    <span><small>增产</small><strong>+{plan.increaseRate}%</strong></span>
                    <span><small>成本</small><strong>¥{plan.cost}/亩</strong></span>
                    <span><small>周期</small><strong>{plan.cycle}天</strong></span>
                    <span><small>风险</small><strong>{plan.risk}</strong></span>
                  </div>

                  <ul>
                    {plan.actions.map((action) => <li key={action}>{action}</li>)}
                  </ul>
                </div>
                <span className="plan-card__check" aria-hidden="true">{selectedPlanId === plan.id ? '✓' : ''}</span>
              </button>
            ))}
          </div>
        </section>
      </div>}
    </div>
  )
}
