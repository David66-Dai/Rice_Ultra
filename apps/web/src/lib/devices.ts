export const DEVICE_STATE_CHANGED = 'smart-rice-device-state-changed'

export const DEVICE_LABELS = {
  pump: '喷药',
  lamp: '驱虫灯',
} as const

export function deviceLabel(device: string): string {
  if (device === 'pump') return DEVICE_LABELS.pump
  if (device === 'lamp') return DEVICE_LABELS.lamp
  return device
}

export function emitDeviceStateChanged() {
  window.dispatchEvent(new Event(DEVICE_STATE_CHANGED))
}

export function linkageHint(
  task: 'leaf' | 'pest',
  activatedDevice?: string | null,
  deviceError?: string | null,
): string | null {
  const name = task === 'leaf' ? DEVICE_LABELS.pump : DEVICE_LABELS.lamp
  if (deviceError) return `${name}指令未发出：${deviceError}`
  if (activatedDevice === 'pump') return '已联动开启喷药'
  if (activatedDevice === 'lamp') return '已联动开启驱虫灯'
  return null
}
