import type { DeviceControlRequest, DeviceControlResponse, DeviceSyncResponse } from '@smart-rice-security/shared'

export type DeviceConnection = 'connecting' | 'connected' | 'reconnecting'
export type DeviceSyncView = {
  snapshot: DeviceSyncResponse | null
  connection: DeviceConnection
  error: string | null
}
type Request = <T>(path: string, options?: {
  method?: 'GET' | 'POST'
  body?: unknown
  signal?: AbortSignal
}) => Promise<T>

/** One authenticated, abortable long poll per signed-in page, across navigation. */
export function createDeviceSyncSession(request: Request, emit: (view: DeviceSyncView) => void) {
  let view: DeviceSyncView = { snapshot: null, connection: 'connecting', error: null }
  let disposed = false
  let generation = 0
  let poll: AbortController | null = null
  let retry: ReturnType<typeof setTimeout> | null = null
  let failures = 0
  let controlling = false
  const mutations = new Set<AbortController>()

  function update(next: Partial<DeviceSyncView>) {
    if (disposed) return
    view = { ...view, ...next }
    emit(view)
  }

  async function synchronize(run: number, immediate: boolean) {
    if (disposed || run !== generation) return
    const controller = new AbortController()
    poll = controller
    let timedOut = false
    const deadline = setTimeout(() => { timedOut = true; controller.abort() }, 35_000)
    const cursor = immediate ? null : view.snapshot?.cursor
    const query = cursor ? `?after=${encodeURIComponent(cursor)}&waitSeconds=25` : ''
    try {
      const snapshot = await request<DeviceSyncResponse>(`/api/devices/sync${query}`, { signal: controller.signal })
      if (disposed || run !== generation) return
      failures = 0
      update({ snapshot, connection: 'connected', error: null })
      // Yield between immediate responses (including reconnects) without overlapping polls.
      retry = setTimeout(() => { void synchronize(run, false) }, 50)
    } catch (error) {
      if (disposed || run !== generation) return
      failures += 1
      update({
        connection: 'reconnecting',
        error: timedOut ? '同步连接超时，正在重新连接' : error instanceof Error ? error.message : '同步中断，正在重新连接',
      })
      retry = setTimeout(() => { void synchronize(run, true) }, Math.min(1000 * 2 ** Math.min(failures - 1, 4), 15_000))
    } finally {
      clearTimeout(deadline)
      if (poll === controller) poll = null
    }
  }

  function refresh() {
    if (disposed) return
    generation += 1
    poll?.abort()
    if (retry !== null) clearTimeout(retry)
    void synchronize(generation, true)
  }

  async function mutate<T>(path: string, body: unknown) {
    if (disposed) throw new Error('当前会话已结束')
    const controller = new AbortController()
    mutations.add(controller)
    const deadline = setTimeout(() => controller.abort(), 15_000)
    try {
      return await request<T>(path, { method: 'POST', body, signal: controller.signal })
    } finally {
      clearTimeout(deadline)
      mutations.delete(controller)
      // Fetch authoritative state even after an uncertain network outcome; never retry a command here.
      refresh()
    }
  }

  return {
    start: refresh,
    refresh,
    async controlDevice(stationId: string, device: 'pump' | 'lamp', enabled: boolean) {
      const snapshot = view.snapshot
      if (view.connection !== 'connected' || !snapshot) throw new Error('设备状态尚未同步，请等待连接恢复')
      if (!snapshot.canControl) throw new Error('当前账号没有设备控制权限，请联系管理员授权')
      if (!snapshot.available) throw new Error('设备控制链路当前不可用')
      if (controlling) throw new Error('上一条控制指令正在处理')
      const state = snapshot.devices.find(item => item.stationId === stationId && item.device === device)
      if (!state) throw new Error('当前站点不支持此设备')
      const body: DeviceControlRequest = { stationId, device, enabled, expectedRevision: state.revision }
      controlling = true
      try {
        await mutate<DeviceControlResponse>('/api/devices/control', body)
      } catch (error) {
        if (error instanceof Error && error.name === 'AbortError') {
          throw new Error('指令响应超时，结果尚未确认；正在同步最新状态，请勿重复操作')
        }
        throw error
      } finally {
        controlling = false
      }
    },
    async markRead() {
      const throughId = Math.max(0, ...view.snapshot?.notifications.map(item => item.id) ?? [])
      if (throughId > 0) await mutate<void>('/api/notifications/read', { throughId })
    },
    dispose() {
      disposed = true
      generation += 1
      poll?.abort()
      mutations.forEach(controller => controller.abort())
      if (retry !== null) clearTimeout(retry)
    },
  }
}
