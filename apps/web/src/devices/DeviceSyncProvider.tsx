import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useAuth } from '../auth/useAuth.ts'
import { createDeviceSyncSession } from '../lib/device-sync.ts'
import type { DeviceSyncView } from '../lib/device-sync.ts'
import { DEVICE_STATE_CHANGED } from '../lib/devices.ts'
import { DeviceSyncContext } from './context.ts'

export function DeviceSyncProvider({ children }: { children: ReactNode }) {
  const { request, user } = useAuth()
  const session = useRef<ReturnType<typeof createDeviceSyncSession> | null>(null)
  const [view, setView] = useState<DeviceSyncView>({ snapshot: null, connection: 'connecting', error: null })

  useEffect(() => {
    const current = createDeviceSyncSession(request, setView)
    session.current = current
    current.start()
    const reconnect = () => current.refresh()
    const visibility = () => { if (document.visibilityState === 'visible') current.refresh() }
    window.addEventListener('online', reconnect)
    window.addEventListener(DEVICE_STATE_CHANGED, reconnect)
    document.addEventListener('visibilitychange', visibility)
    return () => {
      session.current = null
      current.dispose()
      window.removeEventListener('online', reconnect)
      window.removeEventListener(DEVICE_STATE_CHANGED, reconnect)
      document.removeEventListener('visibilitychange', visibility)
    }
  }, [request, user?.id])

  const controlDevice = useCallback(async (stationId: string, device: 'pump' | 'lamp', enabled: boolean) => {
    if (!session.current) throw new Error('设备状态尚未同步')
    await session.current.controlDevice(stationId, device, enabled)
  }, [])
  const setDiagnosisConfirmationRequired = useCallback(async (required: boolean) => {
    if (!session.current) throw new Error('防治策略尚未同步')
    await session.current.setDiagnosisConfirmationRequired(required)
  }, [])
  const markRead = useCallback(async () => { await session.current?.markRead() }, [])
  const value = useMemo(() => ({ ...view, controlDevice, setDiagnosisConfirmationRequired, markRead }),
    [view, controlDevice, setDiagnosisConfirmationRequired, markRead])
  return <DeviceSyncContext.Provider value={value}>{children}</DeviceSyncContext.Provider>
}
