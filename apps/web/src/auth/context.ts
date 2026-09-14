import { createContext } from 'react'
import type { UserInfo } from '@smart-rice-security/shared'

export type LoginVia = 'password' | 'remember' | 'session'

export type AuthState =
  | { status: 'booting'; user: null; accessToken: null; via: null }
  | { status: 'anonymous'; user: null; accessToken: null; via: null }
  | { status: 'authenticated'; user: UserInfo; accessToken: string; via: LoginVia }

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
  /** 'blob' 用于取摄像头快照等二进制响应 */
  responseType?: 'json' | 'blob'
}

export type AuthContextValue = AuthState & {
  /** 上次勾选“记住密码”保存的账号，用于回填 */
  rememberedUsername: string | null
  login: (username: string, password: string, rememberMe: boolean) => Promise<void>
  logout: () => Promise<void>
  /** 携带访问令牌请求；令牌过期时自动用记住登录续期一次 */
  request: <T>(path: string, options?: RequestOptions) => Promise<T>
}

export const ANONYMOUS: AuthState = { status: 'anonymous', user: null, accessToken: null, via: null }
export const BOOTING: AuthState = { status: 'booting', user: null, accessToken: null, via: null }

export const AuthContext = createContext<AuthContextValue | null>(null)
