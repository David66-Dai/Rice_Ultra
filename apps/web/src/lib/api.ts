import type { ApiErrorBody } from '@smart-rice-security/shared'

/** 留空走相对路径 /api（Vite 开发代理）；生产可用 VITE_API_BASE 指定完整地址 */
export const API_BASE: string = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export class NetworkError extends Error {
  constructor(message = '无法连接服务器，请确认后端已启动') {
    super(message)
    this.name = 'NetworkError'
  }
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  token?: string | null
  signal?: AbortSignal
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.token) headers.Authorization = `Bearer ${options.token}`

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method: options.method ?? (options.body !== undefined ? 'POST' : 'GET'),
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new NetworkError()
  }

  const text = await response.text()
  const data: unknown = text ? parseJson(text) : null

  if (!response.ok) {
    const body = (data ?? {}) as Partial<ApiErrorBody>
    throw new ApiError(
      response.status,
      body.code ?? 'http_error',
      body.message ?? `请求失败（HTTP ${response.status}）`,
    )
  }
  return data as T
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError || error instanceof NetworkError) return error.message
  if (error instanceof Error) return error.message
  return '发生未知错误，请稍后重试'
}
