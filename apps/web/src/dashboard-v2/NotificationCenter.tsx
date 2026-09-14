import { useEffect, useId, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useDeviceSync } from '../devices/useDeviceSync.ts'
import { describeError } from '../lib/api.ts'
import { normalizeDeviceMessage } from '../lib/devices.ts'
import './NotificationCenter.css'

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'device_control', label: '设备操作' },
  { key: 'pest_disease', label: '病虫害识别' },
] as const

type NotificationFilter = typeof FILTERS[number]['key']

function BellIcon({ size = 19 }: { size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4M12 2V1" /></svg>
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未提供' : date.toLocaleString('zh-CN', { hour12: false })
}

export function NotificationCenter() {
  const { snapshot, connection, error, markRead } = useDeviceSync()
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState<NotificationFilter>('all')
  const [marking, setMarking] = useState(false)
  const [readError, setReadError] = useState<string | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const markingRef = useRef(false)
  const id = useId()
  const unread = snapshot?.unreadCount ?? 0
  const notifications = (snapshot?.notifications ?? []).filter((item) => filter === 'all' || item.type === filter)
  const syncText = connection === 'connected' ? '实时通知已连接' : connection === 'reconnecting' ? '通知连接中断，正在重连…' : '正在连接通知服务…'

  useEffect(() => {
    if (!open) return
    titleRef.current?.focus()
    function onPointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false)
    }
    function onKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  async function handleMarkRead() {
    if (markingRef.current || unread === 0 || connection !== 'connected') return
    markingRef.current = true
    setMarking(true)
    setReadError(null)
    try {
      await markRead()
    } catch (cause) {
      setReadError(describeError(cause))
    } finally {
      markingRef.current = false
      setMarking(false)
    }
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = index
    if (event.key === 'ArrowRight') next = (index + 1) % FILTERS.length
    else if (event.key === 'ArrowLeft') next = (index - 1 + FILTERS.length) % FILTERS.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = FILTERS.length - 1
    else return
    event.preventDefault()
    setFilter(FILTERS[next].key)
    tabRefs.current[next]?.focus()
  }

  return (
    <div className="notification-center" ref={rootRef} onBlur={(event) => {
      if (event.relatedTarget instanceof Node && !event.currentTarget.contains(event.relatedTarget)) setOpen(false)
    }}>
      <button ref={triggerRef} type="button" className={`notification-trigger${unread ? ' has-unread' : ''}`} aria-label={`通知中心${unread ? `，${unread} 条未读通知` : '，暂无未读通知'}`} aria-expanded={open} aria-haspopup="dialog" aria-controls={`${id}-panel`} title="通知中心" onClick={() => setOpen((value) => !value)}>
        <BellIcon />
        {unread > 0 && <span className="notification-trigger__badge" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
        {connection !== 'connected' && <i className="notification-trigger__connection" aria-hidden="true" />}
      </button>
      <span className="sr-only" role="status" aria-live="polite">{unread > 0 ? `${unread} 条未读通知。${normalizeDeviceMessage(snapshot?.notifications[0]?.message ?? '')}` : ''}</span>

      {open && (
        <section id={`${id}-panel`} className="notification-panel" role="dialog" aria-labelledby={`${id}-title`}>
          <header className="notification-panel__head">
            <div><span>ACTIVITY FEED</span><h2 id={`${id}-title`} ref={titleRef} tabIndex={-1}>通知中心 <small>{unread} 条未读</small></h2></div>
            <button type="button" className="notification-panel__close" aria-label="关闭通知中心" onClick={() => { setOpen(false); triggerRef.current?.focus() }}>×</button>
          </header>
          <div className="notification-panel__tabs" role="tablist" aria-label="通知分类">
            {FILTERS.map((item, index) => <button key={item.key} ref={(node) => { tabRefs.current[index] = node }} type="button" id={`${id}-tab-${item.key}`} role="tab" aria-selected={filter === item.key} aria-controls={`${id}-list`} tabIndex={filter === item.key ? 0 : -1} onClick={() => setFilter(item.key)} onKeyDown={(event) => onTabKeyDown(event, index)}>{item.label}</button>)}
          </div>
          <div className={`notification-panel__connection${connection === 'connected' ? ' is-connected' : ''}`} role="status"><i />{syncText}</div>
          {(error || readError) && <p className="notification-panel__error" role="alert">{readError || error}</p>}
          <div id={`${id}-list`} className="notification-panel__content" role="tabpanel" aria-labelledby={`${id}-tab-${filter}`} tabIndex={0}>
            {notifications.length > 0 ? (
              <ol className="notification-list">
                {notifications.map((item) => <li key={item.id} className={`notification-item notification-item--${item.type}`}>
                  <span className="notification-item__icon" aria-hidden="true">{item.type === 'device_control' ? <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="4" y="4" width="16" height="6" rx="1" /><rect x="4" y="14" width="16" height="6" rx="1" /><path d="M8 7h.01M8 17h.01M12 10v4" /></svg> : <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M5 19C3 9 11 3 20 4c0 10-7 16-15 15Zm0 0 9-9" /></svg>}</span>
                  <div><p>{normalizeDeviceMessage(item.message)}</p><div className="notification-item__meta"><span>{item.type === 'device_control' ? '设备操作' : '病虫害识别'}{item.stationId ? ` · ${item.stationId}` : ''}</span><time dateTime={item.createdAt}>{formatTime(item.createdAt)}</time></div></div>
                </li>)}
              </ol>
            ) : (
              <div className="notification-empty"><BellIcon size={30} /><strong>{!snapshot ? '正在加载通知' : filter === 'pest_disease' ? '暂无病虫害识别通知' : '暂无通知'}</strong><p>{filter === 'pest_disease' ? '黄色/红色识别、AstrBot 消息告警发送失败和风速联锁异常会在这里展示。' : '设备操作成功后，所有用户都会在这里收到通知。'}</p></div>
            )}
          </div>
          <footer className="notification-panel__footer"><span>展示最近通知</span><button type="button" disabled={!unread || marking || connection !== 'connected'} onClick={handleMarkRead}>{marking ? '正在标记…' : '全部标为已读'}</button></footer>
        </section>
      )}
    </div>
  )
}
