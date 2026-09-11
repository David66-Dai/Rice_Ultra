import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { LoginRequest, LoginResponse, LogoutRequest } from '@smart-rice-security/shared'
import { api, ApiError } from '../lib/api'
import { ANONYMOUS, AuthContext, BOOTING } from './context'
import type { AuthContextValue, AuthState, RequestOptions } from './context'
import { bootOnce, exchangeRememberToken } from './session'
import { REMEMBER_KEY, tokenStore } from './storage'

export function AuthProvider({ children }: { children: ReactNode }) {
  const sessionGeneration = useRef(0)
  const [state, setState] = useState<AuthState>(BOOTING)
  const [rememberedUsername, setRememberedUsername] = useState<string | null>(() => tokenStore.getUsername())

  useEffect(() => {
    let cancelled = false
    bootOnce().then((next) => {
      if (cancelled) return
      setState(next)
      setRememberedUsername(tokenStore.getUsername())
    })
    return () => {
      cancelled = true
    }
  }, [])

  // 多标签页同步：其他标签页轮换了记住登录令牌后，本页跟随更新，避免被误判为盗用
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === REMEMBER_KEY && event.newValue) {
        tokenStore.setRemember(event.newValue)
      }
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const login = useCallback(async (username: string, password: string, rememberMe: boolean) => {
    const generation = ++sessionGeneration.current
    const body: LoginRequest = { username: username.trim(), password, rememberMe }
    const res = await api<LoginResponse>('/api/auth/login', { body })
    if (generation !== sessionGeneration.current) return

    tokenStore.setAccess(res.accessToken)
    if (rememberMe && res.rememberToken) {
      tokenStore.setRemember(res.rememberToken)
      tokenStore.setUsername(res.user.username)
      setRememberedUsername(res.user.username)
    } else {
      tokenStore.setRemember(null)
      tokenStore.setUsername(null)
      setRememberedUsername(null)
    }
    setState({ status: 'authenticated', user: res.user, accessToken: res.accessToken, via: 'password' })
  }, [])

  const logout = useCallback(async () => {
    sessionGeneration.current += 1
    const rememberToken = tokenStore.getRemember()
    tokenStore.setAccess(null)
    tokenStore.setRemember(null)
    setState(ANONYMOUS)
    try {
      const body: LogoutRequest = { rememberToken }
      await api<void>('/api/auth/logout', { body })
    } catch {
      /* 服务端不可达时本地已清理，忽略 */
    }
  }, [])

  const request = useCallback(async <T,>(path: string, options: RequestOptions = {}): Promise<T> => {
    const generation = sessionGeneration.current
    try {
      return await api<T>(path, { ...options, token: tokenStore.getAccess() })
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error
      if (generation !== sessionGeneration.current || options.signal?.aborted) throw error
      const refreshed = await exchangeRememberToken(options.signal)
      if (generation !== sessionGeneration.current || options.signal?.aborted) throw error
      if (!refreshed) {
        tokenStore.setAccess(null)
        setState(ANONYMOUS)
        throw error
      }
      setState({ status: 'authenticated', user: refreshed.user, accessToken: refreshed.accessToken, via: 'remember' })
      return await api<T>(path, { ...options, token: refreshed.accessToken })
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ ...state, rememberedUsername, login, logout, request }),
    [state, rememberedUsername, login, logout, request],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
