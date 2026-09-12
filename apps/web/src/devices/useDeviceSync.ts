import { useContext } from 'react'
import { DeviceSyncContext } from './context.ts'

export function useDeviceSync() {
  const value = useContext(DeviceSyncContext)
  if (!value) throw new Error('useDeviceSync must be used within DeviceSyncProvider')
  return value
}
