import { useRef, useState } from 'react'
import { useDeviceSync } from '../devices/useDeviceSync.ts'
import { describeError } from '../lib/api.ts'
import { DEVICE_LABELS, emitDeviceStateChanged } from '../lib/devices.ts'
import './DeviceManagement.css'

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
  configured: index === 0,
}))

const DEVICES = [
  { key: 'pump', name: DEVICE_LABELS.pump, title: '智能喷药', code: 'CHEMICAL SPRAY · P-01' },
  { key: 'lamp', name: DEVICE_LABELS.lamp, title: '智能驱虫灯', code: 'PEST CONTROL LAMP · L-01' },
] as const

function formatTime(value: string | null | undefined) {
  if (!value) return '尚无记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未提供'
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function DeviceManagement() {
  const { snapshot, connection, error, controlDevice } = useDeviceSync()
  const [selectedStationId, setSelectedStationId] = useState('S01')
  const [sending, setSending] = useState<'pump' | 'lamp' | null>(null)
  const sendingRef = useRef(false)
  const [controlError, setControlError] = useState<string | null>(null)
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]
  const canSend = Boolean(snapshot?.canControl && snapshot.available && connection === 'connected' && selectedStation.configured)
  const syncText = connection === 'connected' ? '实时同步已连接' : connection === 'reconnecting' ? '正在重新连接…' : '正在连接…'
  const permissionText = !snapshot
    ? '正在获取设备权限与最新指令状态…'
    : !snapshot.canControl
      ? '当前账号仅可查看。只有被授权的用户可以控制设备，请联系管理员开通权限。'
      : !snapshot.available
        ? '设备控制服务当前不可用，暂时无法发送指令。'
        : connection !== 'connected'
          ? '同步连接中断，显示最近记录；连接恢复后可继续控制设备。'
          : '已获设备控制权限。指令发送成功后，所有在线用户的页面将自动更新。'

  async function sendCommand(device: 'pump' | 'lamp', enabled: boolean) {
    if (!canSend || sendingRef.current) return
    sendingRef.current = true
    setSending(device)
    setControlError(null)
    try {
      await controlDevice(selectedStation.id, device, enabled)
      emitDeviceStateChanged()
    } catch (cause) {
      setControlError(describeError(cause))
    } finally {
      sendingRef.current = false
      setSending(null)
    }
  }

  return (
    <div className="device-page">
      <section className="device-console device-panel" aria-labelledby="device-title">
        <header className="device-head">
          <div>
            <span>FIELD DEVICE CONTROL MATRIX</span>
            <h2 id="device-title">设备管理</h2>
          </div>
          <div className="device-head__controls">
            <label className="device-station-select">
              <span>当前站点</span>
              <select value={selectedStationId} onChange={(event) => {
                setSelectedStationId(event.target.value)
                setControlError(null)
              }}>
                {STATIONS.map((station) => (
                  <option key={station.id} value={station.id}>
                    {station.id} · {station.name}{station.configured ? '' : ' · 离线'}
                  </option>
                ))}
              </select>
            </label>
            <span className={connection === 'connected' ? '' : 'is-offline'} role="status">
              <i />{syncText}
            </span>
          </div>
        </header>

        <div className={`device-permission${snapshot?.canControl ? ' is-authorized' : ''}`} role="status">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
            <path d="m12 3 8 3v5c0 5-4 8-8 10-4-2-8-5-8-10V6l8-3Z" />
            {snapshot?.canControl ? <path d="m8 12 3 3 5-6" /> : <path d="M12 8v5m0 3h.01" />}
          </svg>
          <p>{permissionText}</p>
        </div>

        {selectedStation.configured ? (
          <>
            <div className="device-grid">
              {DEVICES.map((device) => {
                const state = snapshot?.devices.find((item) => item.stationId === selectedStation.id && item.device === device.key)
                const enabled = state?.enabled ?? null
                const status = enabled === null ? '未知' : enabled ? '开启' : '关闭'
                return (
                  <article key={device.key} className={`device-card device-card--${device.key}${enabled === true ? ' is-running' : ''}${enabled === null ? ' is-unknown' : ''}`}>
                    <header>
                      <div className="device-card__identity">
                        <div className={`device-card__icon device-card__icon--${device.key}`} aria-hidden="true"><i /></div>
                        <div><small>{device.code}</small><h3>{device.title}</h3></div>
                      </div>
                      <span className="device-card__status" aria-live="polite"><i />上次指令：{status}</span>
                    </header>

                    <div className={`device-card__visual device-card__visual--${device.key}`} aria-hidden="true">
                      <div className={`${device.key}-core`}><i /><span /></div>
                      <div className={device.key === 'pump' ? 'pump-flow' : 'lamp-wave'}><i /><i /><i /></div>
                    </div>

                    <p className="device-card__feedback">{enabled === null ? state?.updatedAt ? '指令结果不确定，请检查设备实际状态' : '尚无成功发送的指令记录' : '指令已发送 · 实际运行状态待设备反馈'}</p>
                    <dl className="device-card__history">
                      <div><dt>最近操作者</dt><dd title={state?.updatedBy ?? undefined}>{state?.updatedBy ?? '尚无记录'}</dd></div>
                      <div><dt>指令时间</dt><dd title={formatTime(state?.updatedAt)}>{formatTime(state?.updatedAt)}</dd></div>
                    </dl>

                    <div className="device-actions" role="group" aria-label={`${device.name}控制`}>
                      <button type="button" className="device-action device-action--on" disabled={!canSend || sending !== null} onClick={() => sendCommand(device.key, true)}>
                        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 3v9m-5-7a8 8 0 1 0 10 0" /></svg>
                        开启{device.name}
                      </button>
                      <button type="button" className="device-action device-action--off" disabled={!canSend || sending !== null} onClick={() => sendCommand(device.key, false)}>
                        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="1" /></svg>
                        关闭{device.name}
                      </button>
                    </div>
                    <p className="device-card__sending" role="status">{sending === device.key ? '正在发送指令，请稍候…' : !snapshot?.canControl && snapshot ? '仅查看 · 无控制权限' : '开启与关闭均会记录操作者并通知所有用户'}</p>
                  </article>
                )
              })}
            </div>

            <footer className="device-console__footer">
              <span className={controlError || error || connection !== 'connected' ? 'is-error' : ''}><i />{controlError ? '控制指令失败' : syncText}</span>
              <p role={controlError || error ? 'alert' : undefined}>{controlError || error || '显示最近成功发送的指令，不代表设备已确认执行。'}</p>
              <small>所有用户共享指令记录，并与识别告警联动</small>
            </footer>
          </>
        ) : (
          <div className="device-empty" role="status">
            <i aria-hidden="true" />
            <strong>{selectedStation.name}当前离线</strong>
            <p>无法获取设备状态，喷药与驱虫灯控制均不可用</p>
          </div>
        )}
      </section>
    </div>
  )
}
