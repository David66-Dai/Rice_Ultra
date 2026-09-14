import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { IconLogout } from '../components/icons'
import { DonutChart, GaugeChart, Sparkline } from '../dashboard/charts'
import { FieldMap } from '../dashboard/FieldMap'
import {
  ALERT_FEED,
  DEVICE_STATS,
  ENV_METRICS,
  GROWTH_STAGES,
  OVERVIEW,
  PLOTS,
  SUGGESTIONS,
  TASK_STATS,
  TREND_DATA,
  TREND_RANGE_LABEL,
  TREND_RANGES,
  WARNING_RANKS,
  findPlot,
} from '../dashboard/mock'
import type { MapLayer, TrendRange } from '../dashboard/mock'
import { Panel } from '../dashboard/Panel'
import './HomePage.css'

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function nowText() {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 星期${week} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const OVERVIEW_CARDS = [
  { label: '监测面积', value: String(OVERVIEW.area), unit: '亩', tone: 'cyan' },
  { label: '农田地块', value: String(OVERVIEW.plots), unit: '块', tone: 'green' },
  { label: '在线设备', value: String(OVERVIEW.devicesOnline), unit: '台', tone: 'gold' },
  { label: '预警事件', value: String(OVERVIEW.warnings), unit: '条', tone: 'warn' },
] as const

export function HomePage() {
  const auth = useAuth()
  const [leaving, setLeaving] = useState(false)
  const [clock, setClock] = useState(nowText)
  const [selectedId, setSelectedId] = useState('ST-008')
  const [layer, setLayer] = useState<MapLayer>('live')
  const [range, setRange] = useState<TrendRange>('7d')
  const [rankRange, setRankRange] = useState('7d')
  const [taskRange, setTaskRange] = useState('month')

  const plot = useMemo(() => findPlot(selectedId), [selectedId])
  const trends = TREND_DATA[range]
  const activeStage = Math.max(
    0,
    GROWTH_STAGES.findIndex((s) => plot.stage.includes(s)),
  )

  useEffect(() => {
    const id = window.setInterval(() => setClock(nowText()), 1000)
    return () => window.clearInterval(id)
  }, [])

  if (auth.status !== 'authenticated') return null
  const { user } = auth

  async function handleLogout() {
    setLeaving(true)
    await auth.logout()
  }

  return (
    <div className="dash">
      <div className="dash__glow" aria-hidden="true" />

      <header className="dash__top">
        <div className="dash__brand">
          <div>
            <strong>数智稻安</strong>
            <small>科技赋能农业 · 数据守护丰收</small>
          </div>
        </div>

        <div className="dash__title">
          <h1>数智稻田监测预警平台</h1>
          <p>数字农业 · 精准监测 · 智能预警 · 绿色高产</p>
        </div>

        <div className="dash__meta">
          <div className="dash__weather">
            <span>多云 28°C</span>
            <small>示范基地</small>
          </div>
          <div className="dash__chips">
            <span className="chip chip--ok">设备在线 {DEVICE_STATS.online}/{DEVICE_STATS.total}</span>
            <span className="chip chip--warn">预警事件 {OVERVIEW.warnings}</span>
          </div>
          <time className="dash__clock">{clock}</time>
          <div className="dash__user">
            <span className="dash__avatar" aria-hidden="true">
              {user.displayName.slice(0, 1)}
            </span>
            <span className="dash__uname">{user.displayName}</span>
            <button className="dash__out" type="button" onClick={handleLogout} disabled={leaving}>
              <IconLogout size={15} />
              {leaving ? '退出中' : '退出'}
            </button>
          </div>
        </div>
      </header>

      <div className="dash__grid">
        <aside className="dash__col">
          <Panel title="总体概况">
            <div className="kpi">
              {OVERVIEW_CARDS.map((card) => (
                <article key={card.label} className={`kpi__card kpi__card--${card.tone}`}>
                  <span>{card.label}</span>
                  <strong>
                    {card.value}
                    <small>{card.unit}</small>
                  </strong>
                </article>
              ))}
            </div>
          </Panel>

          <Panel title="设备运行概况">
            <div className="split">
              <DonutChart
                percent={Math.round((DEVICE_STATS.online / DEVICE_STATS.total) * 100)}
                label="设备在线率"
                slices={[
                  { value: DEVICE_STATS.online, color: '#3ee08f' },
                  { value: DEVICE_STATS.offline, color: '#8aa0aa' },
                ]}
              />
              <ul className="legend">
                <li>
                  <i className="dot dot--ok" />
                  在线 <b>{DEVICE_STATS.online}</b>
                </li>
                <li>
                  <i className="dot dot--off" />
                  离线 <b>{DEVICE_STATS.offline}</b>
                </li>
                <li>
                  <i className="dot dot--cyan" />
                  总数 <b>{DEVICE_STATS.total}</b>
                </li>
              </ul>
            </div>
          </Panel>

          <Panel title="环境实时数据">
            <div className="env">
              {ENV_METRICS.map((m) => (
                <article key={m.key} className={`env__card env__card--${m.tone}`}>
                  <span>{m.label}</span>
                  <strong>
                    {m.value}
                    <small>{m.unit}</small>
                  </strong>
                  <em className={`env__delta env__delta--${m.trend}`}>{m.delta}</em>
                </article>
              ))}
            </div>
          </Panel>

          <Panel
            title="预警事件排行"
            extra={
              <select className="mini-select" value={rankRange} onChange={(e) => setRankRange(e.target.value)} aria-label="排行时间范围">
                <option value="7d">近7天</option>
                <option value="30d">近30天</option>
              </select>
            }
          >
            <ol className="rank">
              {WARNING_RANKS.map((item) => (
                <li key={item.name}>
                  <span className={`rank__n rank__n--${item.rank}`}>{item.rank}</span>
                  <span className="rank__name">{item.name}</span>
                  <span className="rank__bar">
                    <i style={{ width: `${(item.count / WARNING_RANKS[0].count) * 100}%` }} />
                  </span>
                  <b>{item.count}次</b>
                </li>
              ))}
            </ol>
          </Panel>

          <Panel title="实时告警动态" className="panel--fill">
            <ul className="feed">
              {ALERT_FEED.map((item) => (
                <li key={`${item.time}-${item.plot}`} className={`feed__item feed__item--${item.tone}`}>
                  <button type="button" onClick={() => setSelectedId(item.plot)}>
                    <time>{item.time}</time>
                    <b>{item.plot}</b>
                    <span>{item.text}</span>
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
        </aside>

        <div className="dash__center">
          <FieldMap plots={PLOTS} selectedId={selectedId} layer={layer} onSelect={setSelectedId} onLayer={setLayer} />

          <Panel
            title="环境趋势分析"
            extra={
              <div className="trend-tools">
                <div className="seg" role="tablist" aria-label="趋势时间范围">
                  {TREND_RANGES.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={range === item.id ? 'is-on' : ''}
                      onClick={() => setRange(item.id)}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
                <span className="trend-date">{TREND_RANGE_LABEL[range]}</span>
              </div>
            }
          >
            <div className="sparks">
              {trends.map((s) => (
                <article key={s.key}>
                  <header>
                    <span>{s.label}</span>
                    <b style={{ color: s.color }}>
                      {s.points[s.points.length - 1]}
                      {s.unit}
                    </b>
                  </header>
                  <Sparkline points={s.points} color={s.color} />
                </article>
              ))}
            </div>
          </Panel>
        </div>

        <aside className="dash__col">
          <Panel
            title="地块详情"
            extra={
              <select className="mini-select" value={selectedId} onChange={(e) => setSelectedId(e.target.value)} aria-label="选择地块">
                {PLOTS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.id}
                  </option>
                ))}
              </select>
            }
          >
            <div className="plot">
              <div className="plot__thumb" aria-hidden="true">
                <span>{plot.id}</span>
              </div>
              <dl className="plot__dl">
                <div>
                  <dt>名称</dt>
                  <dd>{plot.name}</dd>
                </div>
                <div>
                  <dt>面积</dt>
                  <dd>{plot.area} 亩</dd>
                </div>
                <div>
                  <dt>作物 / 品种</dt>
                  <dd>
                    {plot.crop} · {plot.variety}
                  </dd>
                </div>
                <div>
                  <dt>当前生长周期</dt>
                  <dd>{plot.stage}</dd>
                </div>
                <div>
                  <dt>预计成熟</dt>
                  <dd>{plot.harvest}</dd>
                </div>
              </dl>
            </div>
          </Panel>

          <Panel title="作物长势分析">
            <div className="growth">
              <GaugeChart value={plot.growth} />
              <ol className="stages">
                {GROWTH_STAGES.map((s, i) => (
                  <li key={s} className={i === activeStage ? 'is-on' : i < activeStage ? 'is-done' : ''}>
                    <i />
                    <span>{s}</span>
                  </li>
                ))}
              </ol>
            </div>
          </Panel>

          <Panel
            title="AI 病虫害识别"
            extra={
              <button type="button" className="link-btn">
                查看记录
              </button>
            }
          >
            <div className="ai">
              <div className="ai__shot" aria-hidden="true">
                <span className="ai__box" />
                <span className="ai__box ai__box--2" />
              </div>
              <div className="ai__res">
                <strong className="tag tag--normal">正常</strong>
                <p>未发现明显病虫害，叶片长势均匀</p>
              </div>
            </div>
          </Panel>

          <Panel title="农事建议">
            <ul className="tips">
              {SUGGESTIONS.map((s) => (
                <li key={s.title} className={`tips__item tips__item--${s.tone}`}>
                  <b>{s.title}</b>
                  <span>{s.detail}</span>
                </li>
              ))}
            </ul>
          </Panel>

          <Panel
            title="任务执行状态"
            extra={
              <select className="mini-select" value={taskRange} onChange={(e) => setTaskRange(e.target.value)} aria-label="任务时间范围">
                <option value="month">本月</option>
                <option value="week">本周</option>
              </select>
            }
          >
            <div className="split">
              <DonutChart
                percent={Math.round((TASK_STATS.done / TASK_STATS.total) * 100)}
                label="完成率"
                slices={[
                  { value: TASK_STATS.done, color: '#3ee08f' },
                  { value: TASK_STATS.doing, color: '#46d7ea' },
                  { value: TASK_STATS.todo, color: '#e8bd5a' },
                ]}
              />
              <ul className="legend">
                <li>
                  <i className="dot dot--ok" />
                  已完成 <b>{TASK_STATS.done}</b>
                </li>
                <li>
                  <i className="dot dot--cyan" />
                  进行中 <b>{TASK_STATS.doing}</b>
                </li>
                <li>
                  <i className="dot dot--gold" />
                  待执行 <b>{TASK_STATS.todo}</b>
                </li>
                <li>
                  <i className="dot dot--off" />
                  合计 <b>{TASK_STATS.total}</b>
                </li>
              </ul>
            </div>
          </Panel>
        </aside>
      </div>
    </div>
  )
}
