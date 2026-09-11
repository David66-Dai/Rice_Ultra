import { useContext } from 'react'
import { AuthContext } from './context'
import type { AuthContextValue } from './context'

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 <AuthProvider> 内使用')
  return ctx
}
