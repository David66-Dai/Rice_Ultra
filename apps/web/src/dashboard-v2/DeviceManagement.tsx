import { useState } from 'react'
import type { DeviceControlRequest, DeviceControlResponse } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth.ts'
import { describeError } from '../lib/api.ts'
import './DeviceManagement.css'

type DeviceState = {
  pump: boolean
  lamp: boolean
}

type DeviceKey = keyof DeviceState

const STATIONS = Array.from({ length: 10 }, (_, index) => ({
  id: `S${String(index + 1).padStart(2, '0')}`,
  name: `${index + 1}号监测站`,
  online: index === 0,
}))

const STORAGE_KEY = 'smart-rice-device-controls'

function loadDeviceState(): DeviceState {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY)
    if (saved) return { pump: false, lamp: false, ...JSON.parse(saved) as Partial<DeviceState> }
  } catch {
    // 使用安全的默认关闭状态
  }
  return { pump: false, lamp: false }
}

function formatTime(date: Date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}

export function DeviceManagement() {
  const auth = useAuth()
  const [selectedStationId, setSelectedStationId] = useState('S01')
  const [stationMenuOpen, setStationMenuOpen] = useState(false)
  const [devices, setDevices] = useState<DeviceState>(loadDeviceState)
  const [lastAction, setLastAction] = useState('尚无控制操作')
  const [sending, setSending] = useState<DeviceKey | null>(null)
  const [controlError, setControlError] = useState<string | null>(null)
  const selectedStation = STATIONS.find((station) => station.id === selectedStationId) ?? STATIONS[0]

  async function toggleDevice(key: DeviceKey) {
    if (!selectedStation.online || sending) return
    const enabled = !devices[key]
    const request: DeviceControlRequest = {
      stationId: selectedStation.id,
      device: key,
      enabled,
    }
    setSending(key)
    setControlError(null)
    try {
      const response = await auth.request<DeviceControlResponse>('/api/devices/control', {
        method: 'POST',
        body: request,
      })
      const next = { ...devices, [key]: enabled }
      setDevices(next)
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      } catch {
        // 浏览器禁用存储时仍保留当前会话状态
      }
      const deviceName = key === 'pump' ? '水泵' : '驱虫灯'
      setLastAction(`${formatTime(new Date(response.sentAt))} · ${deviceName}${enabled ? '已开启' : '已关闭'} · ${response.command}`)
    } catch (error) {
      setControlError(describeError(error))
    } finally {
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
                      <small>IRRIGATION PUMP · P-01</small>
                      <h3>智能灌溉水泵</h3>
                    </div>
                  </div>
                  <span className="device-card__status"><i />{devices.pump ? '运行中' : '已关闭'}</span>
                </header>

                <div className="device-card__visual device-card__visual--pump" aria-hidden="true">
                  <div className="pump-core"><i /><span /></div>
                  <div className="pump-flow"><i /><i /><i /></div>
                </div>

                <dl>
                  <div><dt>工作模式</dt><dd>{devices.pump ? '智能灌溉' : '待机'}</dd></div>
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
                  <strong>{sending === 'pump' ? '正在发送指令…' : devices.pump ? '关闭水泵 · FA03' : '开启水泵 · FA01'}</strong>
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
                  <strong>{sending === 'lamp' ? '正在发送指令…' : devices.lamp ? '关闭驱虫灯 · FA04' : '开启驱虫灯 · FA02'}</strong>
                </button>
              </article>
            </div>

            <footer className="device-console__footer">
              <span className={controlError ? 'is-error' : ''}><i />{controlError ? '控制指令失败' : '控制链路正常'}</span>
              <p>{controlError ? `错误：${controlError}` : `最近操作：${lastAction}`}</p>
              <small>安全策略：设备切换指令需在线站点确认</small>
            </footer>
          </>
        ) : (
          <div className="device-empty" role="status">
            <i aria-hidden="true" />
            <strong>{selectedStation.name}当前离线</strong>
            <p>无法获取设备状态，水泵与驱虫灯控制均不可用</p>
          </div>
        )}
      </section>
    </div>
  )
}
