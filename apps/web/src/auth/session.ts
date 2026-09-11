import type { LoginResponse, RememberRequest, UserInfo } from '@smart-rice-security/shared'
import { api, ApiError } from '../lib/api'
import { ANONYMOUS } from './context'
import type { AuthState } from './context'
import { tokenStore } from './storage'

/** 用记住登录令牌换取新的访问令牌（服务端会轮换令牌，成功后覆盖保存）。 */
let refreshPromise: Promise<LoginResponse | null> | null = null

export function exchangeRememberToken(signal?: AbortSignal): Promise<LoginResponse | null> {
  if (signal?.aborted) return Promise.reject(new DOMException('请求已取消', 'AbortError'))
  // Background synchronization and page requests can expire together. Rotate once.
  refreshPromise ??= rotateRememberToken().finally(() => { refreshPromise = null })
  if (!signal) return refreshPromise
  const pending = refreshPromise
  return new Promise((resolve, reject) => {
    const cancel = () => reject(new DOMException('请求已取消', 'AbortError'))
    signal.addEventListener('abort', cancel, { once: true })
    pending.then(resolve, reject).finally(() => signal.removeEventListener('abort', cancel))
  })
}

async function rotateRememberToken(): Promise<LoginResponse | null> {
  const rememberToken = tokenStore.getRemember()
  if (!rememberToken) return null
  const controller = new AbortController()
  const deadline = setTimeout(() => controller.abort(), 10_000)
  try {
    const body: RememberRequest = { rememberToken }
    const res = await api<LoginResponse>('/api/auth/remember', { body, signal: controller.signal })
    // A response arriving after logout/account switch must not restore the old session.
    if (tokenStore.getRemember() !== rememberToken) return null
    tokenStore.setAccess(res.accessToken)
    tokenStore.setRemember(res.rememberToken)
    tokenStore.setUsername(res.user.username)
    return res
  } catch (error) {
    // 401/403：令牌已失效或被吊销，清掉本地副本；网络错误则保留，下次再试
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)
      && tokenStore.getRemember() === rememberToken) {
      tokenStore.setRemember(null)
      return null
    }
    // A temporary outage should reconnect, without signing an active user out.
    throw error
  } finally {
    clearTimeout(deadline)
  }
}

async function restoreSession(): Promise<AuthState> {
  const access = tokenStore.getAccess()
  if (access) {
    try {
      const user = await api<UserInfo>('/api/auth/me', { token: access })
      return { status: 'authenticated', user, accessToken: access, via: 'session' }
    } catch (error) {
      if (error instanceof ApiError) tokenStore.setAccess(null)
      else return ANONYMOUS // 后端不可达：不要动记住登录令牌
    }
  }
  const remembered = await exchangeRememberToken().catch(() => null)
  if (remembered) {
    return { status: 'authenticated', user: remembered.user, accessToken: remembered.accessToken, via: 'remember' }
  }
  return ANONYMOUS
}

// 模块级单例：React StrictMode 下 effect 会执行两次，
// 避免同一记住令牌被并发使用两次（服务端会视为盗用并吊销）
let bootPromise: Promise<AuthState> | null = null

export function bootOnce(): Promise<AuthState> {
  bootPromise ??= restoreSession()
  return bootPromise
}
