import { useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { IconLogout, IconUser } from '../components/icons'
import { DEVICE_STATS, OVERVIEW } from '../dashboard/mock'
import { loadLiveWeather } from '../lib/weather'
import type { LiveWeather } from '../lib/weather'
import { DecisionSimulation } from './DecisionSimulation.tsx'
import { DeviceManagement } from './DeviceManagement.tsx'
import { EnvironmentAnalysis } from './EnvironmentAnalysis.tsx'
import { FieldInspection } from './FieldInspection.tsx'
import { HistoryTrace } from './HistoryTrace.tsx'
import { HomeOverview } from './HomeOverview.tsx'
import { NotificationCenter } from './NotificationCenter.tsx'
import './MainDashboard.css'

const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

const NAV_ITEMS = [
  { id: 'home', label: '首页', code: 'OVERVIEW' },
  { id: 'inspection', label: '田间巡检', code: 'INSPECTION' },
  { id: 'environment', label: '环境监测', code: 'ENVIRONMENT' },
  { id: 'analysis', label: '决策推演', code: 'ANALYSIS' },
  { id: 'history', label: '历史数据', code: 'HISTORY' },
  { id: 'devices', label: '设备管理', code: 'DEVICES' },
] as const

type NavId = (typeof NAV_ITEMS)[number]['id']

function NavIcon({ id }: { id: NavId }) {
  const common = {
    width: 22,
    height: 22,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  }

  if (id === 'home') {
    return (
      <svg {...common}>
        <path d="m3.5 10 8.5-7 8.5 7" />
        <path d="M5.5 9v11h13V9M9 20v-6h6v6" />
      </svg>
    )
  }
  if (id === 'inspection') {
    return (
      <svg {...common}>
        <path d="M4 19.5V5.2l5-2 6 2 5-2v14.3l-5 2-6-2-5 2Z" />
        <path d="M9 3.2v14.3M15 5.2v14.3" />
        <path d="m11.2 10.8 1.3 1.3 2.5-2.8" />
      </svg>
    )
  }
  if (id === 'environment') {
    return (
      <svg {...common}>
        <path d="M10 5.5a3 3 0 0 0-6 0v8.2a4.5 4.5 0 1 0 6 0V5.5Z" />
        <path d="M7 8v7.2" />
        <path d="M14 7c2.8-3 5.7-1.8 6.5-1.3-.2 3.8-2.3 6.3-6.5 5.7" />
        <path d="M13 15c2.4-1.1 4.5-.2 6 2.3" />
      </svg>
    )
  }
  if (id === 'analysis') {
    return (
      <svg {...common}>
        <path d="M4 19V5M4 19h16" />
        <path d="m7 15 3.5-4 3 2 5-6" />
        <circle cx="7" cy="15" r=".8" fill="currentColor" stroke="none" />
        <circle cx="10.5" cy="11" r=".8" fill="currentColor" stroke="none" />
        <circle cx="13.5" cy="13" r=".8" fill="currentColor" stroke="none" />
        <circle cx="18.5" cy="7" r=".8" fill="currentColor" stroke="none" />
      </svg>
    )
  }
  if (id === 'history') {
    return (
      <svg {...common}>
        <path d="M4.2 8.2A8.2 8.2 0 1 1 4 15" />
        <path d="M4 4v4.5h4.5M12 7.5V12l3.1 2" />
      </svg>
    )
  }
  return (
    <svg {...common}>
      <rect x="4" y="3.5" width="16" height="6" rx="1.5" />
      <rect x="4" y="14.5" width="16" height="6" rx="1.5" />
      <path d="M8 6.5h.01M8 17.5h.01M12 9.5v5" />
    </svg>
  )
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function getFarmingPeriod(date: Date) {
  const day = (date.getMonth() + 1) * 100 + date.getDate()

  if (day <= 220) return '冬闲养地期'
  if (day <= 320) return '春耕备种期'
  if (day <= 425) return '清明 · 育秧期'
  if (day <= 605) return '谷雨 · 移栽期'
  if (day <= 705) return '芒种 · 返青分蘖期'
  if (day <= 805) return '小暑 · 拔节孕穗期'
  if (day <= 907) return '处暑 · 抽穗扬花期'
  if (day <= 923) return '白露 · 灌浆成熟期'
  if (day <= 1023) return '秋分 · 收获准备期'
  if (day <= 1122) return '霜降 · 秋收归仓期'
  return '冬藏 · 土壤养护期'
}

export function MainDashboard() {
  const auth = useAuth()
  const [now, setNow] = useState(() => new Date())
  const [weather, setWeather] = useState<LiveWeather | null>(null)
  const [locationFailed, setLocationFailed] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [activeNav, setActiveNav] = useState<NavId>('home')
  const [visibleNav, setVisibleNav] = useState<NavId>('home')
  const [pageLeaving, setPageLeaving] = useState(false)
  const pageSwitchTimer = useRef<number | null>(null)

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    loadLiveWeather(controller.signal)
      .then(setWeather)
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setLocationFailed(true)
        }
      })
    return () => controller.abort()
  }, [])

  useEffect(() => () => {
    if (pageSwitchTimer.current !== null) {
      window.clearTimeout(pageSwitchTimer.current)
    }
  }, [])

  const dateText = useMemo(
    () => `${now.getFullYear()}年${pad(now.getMonth() + 1)}月${pad(now.getDate())}日`,
    [now],
  )
  const timeText = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  const visibleItem = NAV_ITEMS.find((item) => item.id === visibleNav) ?? NAV_ITEMS[0]

  if (auth.status !== 'authenticated') return null
  const { user } = auth

  async function handleLogout() {
    setLeaving(true)
    await auth.logout()
  }

  function handleNavigation(next: NavId) {
    if (next === activeNav) return

    if (pageSwitchTimer.current !== null) {
      window.clearTimeout(pageSwitchTimer.current)
    }
    setActiveNav(next)
    setPageLeaving(true)
    pageSwitchTimer.current = window.setTimeout(() => {
      setVisibleNav(next)
      setPageLeaving(false)
      pageSwitchTimer.current = null
    }, 180)
  }

  return (
    <main className="monitor">
      <div className="monitor__intro" role="status" aria-live="polite">
        <div className="monitor__intro-grid" aria-hidden="true" />
        <div className="monitor__intro-core">
          <span>AUTHORIZATION PASSED</span>
          <strong>系统认证通过</strong>
          <i aria-hidden="true" />
          <small>正在载入数字孪生农田数据</small>
        </div>
      </div>

      <div className="monitor__frame">
        <header className="command-header">
          <div className="command-header__left">
            <div className="location" title={locationFailed ? '暂时无法获取实时位置' : '根据当前位置实时定位'}>
              <span className="location__radar" aria-hidden="true">
                <i />
              </span>
              <div>
                <span className="location__label">实时城市</span>
                <strong>{weather?.city ?? (locationFailed ? '位置未获取' : '正在定位')}</strong>
                {weather && (
                  <small>
                    {weather.weatherIcon} {weather.weather} {Math.round(weather.temperature)}℃
                  </small>
                )}
              </div>
            </div>

            <div className="datetime">
              <time dateTime={now.toISOString()} className="datetime__time">
                {timeText}
              </time>
              <div className="datetime__date">
                <span>{dateText}</span>
                <b>{WEEKDAYS[now.getDay()]}</b>
              </div>
            </div>

            <div className="farming-time">
              <span>当前农时</span>
              <strong>{getFarmingPeriod(now)}</strong>
            </div>
          </div>

          <div className="command-header__brand" aria-label="数智稻安">
            <span className="brand-wing brand-wing--left" aria-hidden="true" />
            <div>
              <small>SMART RICE SECURITY</small>
              <h1 data-text="数智稻安">数智稻安</h1>
              <p>稻田智能预警与人机协同管控平台</p>
            </div>
            <span className="brand-wing brand-wing--right" aria-hidden="true" />
          </div>

          <div className="command-header__right">
            <div className="header-stat">
              <span className="header-stat__icon header-stat__icon--plot" aria-hidden="true" />
              <div>
                <span>检测地块</span>
                <strong>{OVERVIEW.plots}<small>块</small></strong>
              </div>
            </div>

            <div className="header-stat">
              <span className="header-stat__icon header-stat__icon--device" aria-hidden="true">
                <i />
              </span>
              <div>
                <span>在线设备</span>
                <strong>
                  {DEVICE_STATS.online}<small> / {DEVICE_STATS.total}台</small>
                </strong>
              </div>
            </div>

            <div className="account">
              <span className="account__avatar" aria-hidden="true">
                <IconUser size={17} />
              </span>
              <div className="account__info">
                <span>当前账号</span>
                <strong>{user.displayName}</strong>
                <small>@{user.username}</small>
              </div>
            </div>

            <NotificationCenter />
            <button className="logout-button" type="button" onClick={handleLogout} disabled={leaving} aria-label={leaving ? '正在退出登录' : '退出登录'} title="退出登录">
              <IconLogout size={17} />
              <span>{leaving ? '退出中' : '退出登录'}</span>
            </button>
          </div>
        </header>

        <section className="monitor__content" aria-label="主界面内容区域">
          <nav className="side-nav" aria-label="系统功能导航">
            <div className="side-nav__head">
              <span className="side-nav__head-icon" aria-hidden="true">
                <i />
              </span>
              <div>
                <strong>功能导航</strong>
                <small>FUNCTION MATRIX</small>
              </div>
            </div>

            <div className="side-nav__list">
              {NAV_ITEMS.map((item, index) => (
                <button
                  key={item.id}
                  type="button"
                  className={`side-nav__item${activeNav === item.id ? ' is-active' : ''}`}
                  aria-current={activeNav === item.id ? 'page' : undefined}
                  onClick={() => handleNavigation(item.id)}
                >
                  <span className="side-nav__index">{pad(index + 1)}</span>
                  <span className="side-nav__icon">
                    <NavIcon id={item.id} />
                  </span>
                  <span className="side-nav__text">
                    <strong>{item.label}</strong>
                    <small>{item.code}</small>
                  </span>
                  <i className="side-nav__arrow" aria-hidden="true" />
                </button>
              ))}
            </div>

            <div className="side-nav__status">
              <span className="side-nav__status-light" aria-hidden="true" />
              <div>
                <strong>SYSTEM ONLINE</strong>
                <small>系统运行正常</small>
              </div>
            </div>
          </nav>

          <div className="monitor__workspace" data-section={visibleNav}>
            <div
              key={visibleNav}
              className={`monitor__page${pageLeaving ? ' is-leaving' : ''}`}
            >
              {visibleNav === 'home' ? (
                <HomeOverview />
              ) : visibleNav === 'inspection' ? (
                <FieldInspection />
              ) : visibleNav === 'environment' ? (
                <EnvironmentAnalysis />
              ) : visibleNav === 'analysis' ? (
                <DecisionSimulation />
              ) : visibleNav === 'history' ? (
                <HistoryTrace />
              ) : visibleNav === 'devices' ? (
                <DeviceManagement />
              ) : (
                <section className="module-placeholder" aria-label={`${visibleItem.label}页面`}>
                  <span>{visibleItem.code}</span>
                  <h2>{visibleItem.label}</h2>
                  <p>功能模块正在建设中</p>
                </section>
              )}
            </div>
          </div>
        </section>
      </div>
    </main>
  )
}
