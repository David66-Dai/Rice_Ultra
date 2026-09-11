import { useCallback, useEffect, useRef, useState } from 'react'
import type { DeviceControlRequest, DeviceControlResponse, DeviceStatusResponse } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import { DEVICE_LABELS, DEVICE_STATE_CHANGED, deviceLabel, emitDeviceStateChanged } from '../lib/devices.ts'
import './DeviceManagement.css'

type DeviceSwitches = {
  pump: boolean
  lamp: boolean
}

type DeviceKey = keyof DeviceSwitches

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
  online: index === 0,
}))

function formatTime(date: Date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}

export function DeviceManagement() {
  const auth = useAuth()
  const [selectedStationId, setSelectedStationId] = useState('S01')
  const [stationMenuOpen, setStationMenuOpen] = useState(false)
  const [devices, setDevices] = useState<DeviceSwitches>({ pump: false, lamp: false })
  const [lastAction, setLastAction] = useState('尚无控制操作')
  const [sending, setSending] = useState<DeviceKey | null>(null)
  const [controlError, setControlError] = useState<string | null>(null)
  const sendingRef = useRef<DeviceKey | null>(null)
  const hydratedRef = useRef(false)
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]

  const loadState = useCallback(async () => {
    if (selectedStationId !== 'S01' || sendingRef.current) return
    try {
      const status = await auth.request<DeviceStatusResponse>(
        `/api/devices/state?stationId=${encodeURIComponent(selectedStationId)}`,
      )
      setDevices({ pump: status.pump, lamp: status.lamp })
      setControlError(null)
      if (!hydratedRef.current) {
        setLastAction('已同步现场状态')
        hydratedRef.current = true
      }
    } catch (error) {
      setControlError(describeError(error))
    }
  }, [auth, selectedStationId])

  useEffect(() => {
    if (!selectedStation.online) return undefined
    void loadState()
    const timer = window.setInterval(() => { void loadState() }, 5_000)
    const onChange = () => { void loadState() }
    window.addEventListener(DEVICE_STATE_CHANGED, onChange)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener(DEVICE_STATE_CHANGED, onChange)
    }
  }, [loadState, selectedStation.online])

  async function toggleDevice(key: DeviceKey) {
    if (!selectedStation.online || sending) return
    const enabled = !devices[key]
    const request: DeviceControlRequest = {
      stationId: selectedStation.id,
      device: key,
      enabled,
    }
    sendingRef.current = key
    setSending(key)
    setControlError(null)
    try {
      const response = await auth.request<DeviceControlResponse>('/api/devices/control', {
        method: 'POST',
        body: request,
      })
      setDevices((current) => ({ ...current, [key]: response.enabled }))
      setLastAction(`${formatTime(new Date(response.sentAt))} · ${deviceLabel(key)}${enabled ? '已开启' : '已关闭'} · ${response.command}`)
      emitDeviceStateChanged()
    } catch (error) {
      setControlError(describeError(error))
    } finally {
      sendingRef.current = null
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
                <div className="station-switch__menu" role="listbox" aria-label="选择设备站点">
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
              <i />{selectedStation.id} 设备{selectedStation.online ? '在线' : '离线'}
            </span>
          </div>
        </header>

        {selectedStation.online ? (
          <>
            <div className="device-grid">
              <article className={`device-card device-card--pump${devices.pump ? ' is-running' : ''}`}>
                <header>
                  <div className="device-card__identity">
                    <div className="device-card__icon device-card__icon--pump" aria-hidden="true"><i /></div>
                    <div>
                      <small>CHEMICAL SPRAY · P-01</small>
                      <h3>智能喷药</h3>
                    </div>
                  </div>
                  <span className="device-card__status"><i />{devices.pump ? '运行中' : '已关闭'}</span>
                </header>

                <div className="device-card__visual device-card__visual--pump" aria-hidden="true">
                  <div className="pump-core"><i /><span /></div>
                  <div className="pump-flow"><i /><i /><i /></div>
                </div>

                <dl>
                  <div><dt>工作模式</dt><dd>{devices.pump ? '叶害喷药' : '待机'}</dd></div>
                  <div><dt>瞬时流量</dt><dd>{devices.pump ? '18.6' : '0.0'}<small>m³/h</small></dd></div>
                  <div><dt>运行功率</dt><dd>{devices.pump ? '1.5' : '0.0'}<small>kW</small></dd></div>
                  <div><dt>管路压力</dt><dd>{devices.pump ? '0.32' : '0.00'}<small>MPa</small></dd></div>
                </dl>

                <button
                  type="button"
                  className="device-switch"
                  role="switch"
                  aria-checked={devices.pump}
                  disabled={sending !== null}
                  onClick={() => toggleDevice('pump')}
                >
                  <span><i /></span>
                  <strong>{sending === 'pump' ? '正在发送指令…' : devices.pump ? `关闭${DEVICE_LABELS.pump} · FA03` : `开启${DEVICE_LABELS.pump} · FA01`}</strong>
                </button>
              </article>

              <article className={`device-card device-card--lamp${devices.lamp ? ' is-running' : ''}`}>
                <header>
                  <div className="device-card__identity">
                    <div className="device-card__icon device-card__icon--lamp" aria-hidden="true"><i /></div>
                    <div>
                      <small>PEST CONTROL LAMP · L-01</small>
                      <h3>智能驱虫灯</h3>
                    </div>
                  </div>
                  <span className="device-card__status"><i />{devices.lamp ? '运行中' : '已关闭'}</span>
                </header>

                <div className="device-card__visual device-card__visual--lamp" aria-hidden="true">
                  <div className="lamp-core"><i /><span /></div>
                  <div className="lamp-wave"><i /><i /><i /></div>
                </div>

                <dl>
                  <div><dt>工作模式</dt><dd>{devices.lamp ? '光控诱虫' : '待机'}</dd></div>
                  <div><dt>诱虫波长</dt><dd>365<small>nm</small></dd></div>
                  <div><dt>运行功率</dt><dd>{devices.lamp ? '24' : '0'}<small>W</small></dd></div>
                  <div><dt>覆盖面积</dt><dd>120<small>m²</small></dd></div>
                </dl>

                <button
                  type="button"
                  className="device-switch"
                  role="switch"
                  aria-checked={devices.lamp}
                  disabled={sending !== null}
                  onClick={() => toggleDevice('lamp')}
                >
                  <span><i /></span>
                  <strong>{sending === 'lamp' ? '正在发送指令…' : devices.lamp ? `关闭${DEVICE_LABELS.lamp} · FA04` : `开启${DEVICE_LABELS.lamp} · FA02`}</strong>
                </button>
              </article>
            </div>

            <footer className="device-console__footer">
              <span className={controlError ? 'is-error' : ''}><i />{controlError ? '控制指令失败' : '控制链路正常'}</span>
              <p>{controlError ? `错误：${controlError}` : `最近操作：${lastAction}`}</p>
              <small>安全策略：设备切换指令需在线站点确认；状态与田间巡检联动同步</small>
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
