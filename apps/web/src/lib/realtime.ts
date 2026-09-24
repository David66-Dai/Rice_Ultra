import { ApiError } from './api.ts'

/** 服务端最长挂起时长；到点后返回当前快照，客户端立即续上下一轮 */
export const REALTIME_WAIT_SECONDS = 25

type Request = <T>(path: string, options?: { signal?: AbortSignal }) => Promise<T>

type RealtimeHandlers<T> = {
  onData: (value: T) => void
  onError?: (error: unknown) => void
}

/**
 * 实时采集长轮询：请求带上已渲染的采样时间挂在服务端，串口写入新采样就立刻返回并写入界面，
 * 因此没有任何固定刷新间隔。返回值用于结束这次订阅。
 */
export function watchRealtime<T>(
  request: Request,
  path: string,
  cursorOf: (value: T) => string,
  handlers: RealtimeHandlers<T>,
): () => void {
  let disposed = false
  let cursor: string | null = null
  let poll: AbortController | null = null
  let retry: ReturnType<typeof setTimeout> | null = null
  let failures = 0

  async function synchronize() {
    if (disposed) return
    const controller = new AbortController()
    poll = controller
    let timedOut = false
    const deadline = setTimeout(() => { timedOut = true; controller.abort() }, (REALTIME_WAIT_SECONDS + 10) * 1000)
    const query = cursor === null
      ? ''
      : `${path.includes('?') ? '&' : '?'}after=${encodeURIComponent(cursor)}&waitSeconds=${REALTIME_WAIT_SECONDS}`
    try {
      const value = await request<T>(`${path}${query}`, { signal: controller.signal })
      if (disposed) return
      failures = 0
      cursor = cursorOf(value)
      handlers.onData(value)
      // 服务端已按变化放行，这里只让出一次事件循环，避免同一轮内重入
      retry = setTimeout(() => { void synchronize() }, 0)
    } catch (error) {
      if (disposed) return
      // 站点尚未产出首帧：以空游标继续挂起，等第一条采样到达即刻写入
      if (error instanceof ApiError && error.status === 404) {
        cursor = ''
        handlers.onError?.(error)
        retry = setTimeout(() => { void synchronize() }, 0)
        return
      }
      failures += 1
      handlers.onError?.(timedOut ? new Error('实时数据连接超时，正在重新连接') : error)
      retry = setTimeout(() => { void synchronize() }, Math.min(1000 * 2 ** Math.min(failures - 1, 4), 15_000))
    } finally {
      clearTimeout(deadline)
      if (poll === controller) poll = null
    }
  }

  void synchronize()
  return () => {
    disposed = true
    poll?.abort()
    if (retry !== null) clearTimeout(retry)
  }
}
