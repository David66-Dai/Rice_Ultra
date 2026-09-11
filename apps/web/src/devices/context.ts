import { createContext } from 'react'
import type { DeviceSyncView } from '../lib/device-sync.ts'

export type DeviceSyncContextValue = DeviceSyncView & {
  controlDevice: (stationId: string, device: 'pump' | 'lamp', enabled: boolean) => Promise<void>
  markRead: () => Promise<void>
}

export const DeviceSyncContext = createContext<DeviceSyncContextValue | null>(null)
