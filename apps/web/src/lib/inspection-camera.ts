export type CameraStatus = 'requesting' | 'live' | 'offline' | 'denied' | 'unavailable' | 'error'

export type CameraState = {
  devices: MediaDeviceInfo[]
  selectedDeviceId: string
  status: CameraStatus
  message: string
  stream: MediaStream | null
}

export function cameraErrorText(error: unknown) {
  const name = error instanceof Error ? error.name : ''
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
    return '浏览器未授权摄像头，请允许后重新连接'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return '未检测到所选摄像头，请检查连接或选择其他摄像头'
  if (name === 'NotReadableError') return '摄像头无法读取，可能被其他程序占用，请选择其他摄像头或重试'
  return '摄像头启动失败，请检查设备连接'
}

// Owns one preview stream. Device IDs, rather than enumeration order, identify cameras.
export function createCameraSession(media: MediaDevices, onChange: (state: CameraState) => void) {
  let state: CameraState = {
    devices: [], selectedDeviceId: '', status: 'requesting', message: '正在连接摄像头…', stream: null,
  }
  let disposed = false
  let requestVersion = 0
  let enumerationVersion = 0
  let removeEndedListener: (() => void) | undefined

  function update(patch: Partial<CameraState>) {
    if (disposed) return
    state = { ...state, ...patch }
    onChange(state)
  }

  function releaseStream() {
    removeEndedListener?.()
    removeEndedListener = undefined
    state.stream?.getTracks().forEach(track => track.stop())
    state = { ...state, stream: null }
  }

  async function refresh(reconnect = true) {
    const version = ++enumerationVersion
    try {
      const listed = await media.enumerateDevices()
      if (disposed || version !== enumerationVersion) return
      const devices = listed.filter((device, index) => (
        device.kind === 'videoinput' && device.deviceId &&
        listed.findIndex(item => item.kind === 'videoinput' && item.deviceId === device.deviceId) === index
      ))
      update({ devices })
      if (!reconnect || state.status === 'requesting' || state.status === 'denied') return
      if (!devices.length) {
        ++requestVersion
        releaseStream()
        update({ selectedDeviceId: '', stream: null, status: 'unavailable', message: '未检测到可用摄像头，请连接设备后重新检测' })
      } else if (!devices.some(device => device.deviceId === state.selectedDeviceId) || !state.stream) {
        const target = devices.find(device => device.deviceId === state.selectedDeviceId) ?? devices[0]
        await select(target.deviceId)
      }
    } catch {
      if (!disposed && version === enumerationVersion) {
        update({ message: state.stream ? '视频已连接，摄像头列表读取失败，请重新检测' : '摄像头列表读取失败，请重新检测' })
      }
    }
  }

  async function select(deviceId = '') {
    if (disposed) return
    const version = ++requestVersion
    releaseStream()
    update({ selectedDeviceId: deviceId, stream: null, status: 'requesting', message: '正在连接摄像头…' })
    try {
      const stream = await media.getUserMedia({
        video: {
          ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
          width: { ideal: 1920 }, height: { ideal: 1080 },
        },
        audio: false,
      })
      if (disposed || version !== requestVersion) {
        stream.getTracks().forEach(track => track.stop())
        return
      }
      const track = stream.getVideoTracks()[0]
      const ended = () => {
        if (disposed || state.stream !== stream) return
        releaseStream()
        update({ stream: null, status: 'unavailable', message: '摄像头已断开，正在重新检测…' })
        void refresh()
      }
      track?.addEventListener('ended', ended)
      removeEndedListener = () => track?.removeEventListener('ended', ended)
      update({ stream, selectedDeviceId: track?.getSettings().deviceId || deviceId, status: 'live', message: '传输正常' })
    } catch (error) {
      if (disposed || version !== requestVersion) return
      const name = error instanceof Error ? error.name : ''
      const denied = name === 'NotAllowedError' || name === 'PermissionDeniedError'
      const missing = name === 'NotFoundError' || name === 'OverconstrainedError'
      update({ status: denied ? 'denied' : missing ? 'unavailable' : 'error', message: cameraErrorText(error) })
    }
    // Permission unlocks the complete list and device labels. Also list alternatives
    // when the default camera is busy; enumeration failure must not stop live video.
    if (!disposed && version === requestVersion) await refresh(false)
  }

  const deviceChanged = () => { void refresh() }
  media.addEventListener('devicechange', deviceChanged)

  return {
    start: () => select(),
    select,
    refresh: () => refresh(),
    retry: () => select(state.selectedDeviceId),
    dispose() {
      disposed = true
      ++requestVersion
      ++enumerationVersion
      media.removeEventListener('devicechange', deviceChanged)
      releaseStream()
    },
  }
}
