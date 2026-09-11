import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { HealthResponse } from '@smart-rice-security/shared'
import loginBackground from '../assets/login_background.png'
import { useAuth } from '../auth/useAuth'
import { IconAlert, IconArrowRight, IconCheck, IconEye, IconEyeOff, IconLock, IconUser } from '../components/icons'
import { api, describeError } from '../lib/api'
import { loadLiveWeather } from '../lib/weather'
import type { LiveWeather } from '../lib/weather'
import './LoginPage.css'

type HealthState = 'checking' | 'online' | 'offline'

const HEALTH_LABEL: Record<HealthState, string> = {
  checking: '检测中',
  online: '在线',
  offline: '离线',
}

function todayText() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

export function LoginPage() {
  const { login, rememberedUsername } = useAuth()
  const ids = {
    username: useId(),
    password: useId(),
    remember: useId(),
    error: useId(),
  }

  const [username, setUsername] = useState(rememberedUsername ?? '')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(Boolean(rememberedUsername))
  const [showPassword, setShowPassword] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [health, setHealth] = useState<HealthState>('checking')
  const [weather, setWeather] = useState<LiveWeather | null>(null)
  const [weatherUnavailable, setWeatherUnavailable] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(() => controller.abort(), 5000)
    api<HealthResponse>('/api/health', { signal: controller.signal })
      .then((res) => setHealth(res.status === 'ok' ? 'online' : 'offline'))
      .catch(() => setHealth('offline'))
      .finally(() => window.clearTimeout(timer))
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(() => controller.abort(), 12000)
    loadLiveWeather(controller.signal)
      .then(setWeather)
      .catch(() => setWeatherUnavailable(true))
      .finally(() => window.clearTimeout(timer))
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return

    const name = username.trim()
    if (!name || !password) {
      setError('请输入账号和密码')
      return
    }

    setSubmitting(true)
    setError(null)
    try {
      await login(name, password, rememberMe)
    } catch (err) {
      setError(describeError(err))
      setPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login">
      <div className="login__bg" style={{ backgroundImage: `url(${loginBackground})` }} aria-hidden="true" />
      <div className="login__shade" aria-hidden="true" />

      <header className="login__top">
        <div className="login__brand">
          <div>
            <h1>数智稻安</h1>
            <p>水稻农田智能监测预警平台</p>
          </div>
        </div>
        <p className="login__brand-slogan">科 技 守 护 稻 田　·　数 据 赋 能 农 业</p>

        <div className="login__top-stats">
          <article title={weather ? `实时空气质量指数 AQI ${weather.aqi}` : undefined}>
            <span className="login__stat-icon" aria-hidden="true">{weather?.weatherIcon ?? '◌'}</span>
            <p>
              {weather ? (
                <>
                  <strong>{weather.city}　{Math.round(weather.temperature)}°C</strong>
                  <small>{weather.weather}　空气{weather.airQuality} · AQI {weather.aqi}</small>
                </>
              ) : (
                <>
                  <strong>{weatherUnavailable ? '天气暂不可用' : '正在获取实时天气'}</strong>
                  <small>{weatherUnavailable ? '请检查网络连接' : '城市 · 温度 · 天气 · 空气质量'}</small>
                </>
              )}
            </p>
          </article>
          <article>
            <span className="login__stat-icon" aria-hidden="true">⌁</span>
            <p><strong>站点总数</strong><small>10</small></p>
          </article>
          <article>
            <span className="login__stat-icon" aria-hidden="true">◉</span>
            <p><strong>在线率</strong><small>98%</small></p>
          </article>
          <article>
            <span className="login__stat-icon" aria-hidden="true">▣</span>
            <p><strong>今日日期</strong><small>{todayText()}</small></p>
          </article>
        </div>
      </header>

      <section className="login__hero-copy">
        <p>从 一 粒 种 子</p>
        <p>到 一 片 丰 收 的 未 来</p>
        <span>SMART AGRICULTURE<br />FOR A BETTER TOMORROW</span>
      </section>

      <div className="login__sensor login__sensor--temp" aria-hidden="true">
        <span>♨</span>
        <p>
          <small>气象监测</small>
          <strong>
            {weather ? `${weather.temperature.toFixed(1)}°C　` : '--°C　'}
            <i>{weather?.airQuality ?? '--'}</i>
          </strong>
        </p>
      </div>
      <div className="login__sensor login__sensor--soil" aria-hidden="true">
        <span>◒</span>
        <p><small>土壤湿度</small><strong>24.1°C　32%</strong></p>
      </div>
      <div className="login__sensor login__sensor--growth" aria-hidden="true">
        <span>❧</span>
        <p><small>作物长势</small><strong>正常生长</strong></p>
      </div>

      <aside className="login__panel">
        <span className="login__panel-glow" aria-hidden="true" />
        <div className="login__intro">
          <h2>欢迎登录</h2>
          <p>数智稻安 · 水稻农田智能监测预警平台</p>
        </div>

        <form className="login__form" onSubmit={handleSubmit} noValidate>
          <label className="login-field" htmlFor={ids.username}>
            <span className="login-field__icon"><IconUser /></span>
            <input
              id={ids.username}
              type="text"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              placeholder="请输入账号 / 用户名"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoFocus={!rememberedUsername}
              disabled={submitting}
            />
          </label>

          <label className="login-field" htmlFor={ids.password}>
            <span className="login-field__icon"><IconLock /></span>
            <input
              id={ids.password}
              type={showPassword ? 'text' : 'password'}
              name="password"
              autoComplete="current-password"
              placeholder="请输入密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus={Boolean(rememberedUsername)}
              disabled={submitting}
            />
            <button
              type="button"
              className="login-field__toggle"
              onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? '隐藏密码' : '显示密码'}
              aria-pressed={showPassword}
            >
              {showPassword ? <IconEyeOff /> : <IconEye />}
            </button>
          </label>

          <div className="login__row">
            <label className="login-check" htmlFor={ids.remember}>
              <input
                id={ids.remember}
                className="sr-only"
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                disabled={submitting}
              />
              <span className="login-check__box" aria-hidden="true"><IconCheck /></span>
              <span>记住密码</span>
            </label>
          </div>

          {error ? (
            <p id={ids.error} className="login__error" role="alert">
              <IconAlert size={15} />
              <span>{error}</span>
            </p>
          ) : null}

          <button className="login__submit" type="submit" disabled={submitting} aria-describedby={error ? ids.error : undefined}>
            {submitting ? (
              <><span className="spinner" aria-hidden="true" />登录中</>
            ) : (
              <>登录系统 <span className="login__submit-arrow" aria-hidden="true"><IconArrowRight size={14} /></span></>
            )}
          </button>
        </form>

        <footer className="login__support">
          <span className={`login__service login__service--${health}`} role="status">
            <i /> 系统{HEALTH_LABEL[health]}
          </span>
          <span>技术支持:</span>
          <span>联系管理员</span>
        </footer>
      </aside>

      <nav className="login__features" aria-label="平台能力">
        <span><b>⌖</b> 感知农田</span>
        <span><b>▥</b> 数据分析</span>
        <span><b>◇</b> 预警预报</span>
        <span><b>❧</b> 科学种植</span>
      </nav>

      <footer className="login__footer">
        <span>© 2026 数智稻安　水稻农田智能监测预警平台</span>
        <span>|　Smart Agriculture Platform　|　科技守护稻田　数据赋能农业</span>
      </footer>
    </main>
  )
}
