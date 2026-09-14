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

/** Keeps previously stored notifications aligned with the current device name. */
export function normalizeDeviceMessage(message: string): string {
  return message
    .replaceAll('智能灌溉水泵', '智能喷药')
    .replaceAll('水泵', '喷药')
}

export function emitDeviceStateChanged() {
  window.dispatchEvent(new Event(DEVICE_STATE_CHANGED))
}

export function linkageHint(
  task: 'leaf' | 'pest',
  activatedDevice?: string | null,
  deviceError?: string | null,
  confirmationRequired?: boolean,
  pendingConfirmationId?: string | null,
  alertDeliveryStatus?: string | null,
): string | null {
  const name = task === 'leaf' ? DEVICE_LABELS.pump : DEVICE_LABELS.lamp
  if (deviceError) return `${name}指令未发出：${deviceError}`
  if (confirmationRequired) {
    const delivery = alertDeliveryStatus === 'FAILED' ? '微信告警发送失败，设备不会开启'
      : alertDeliveryStatus === 'SENT' ? '等待微信确认' : '微信告警发送中'
    return `${delivery}${pendingConfirmationId ? ` · 确认编号 ${pendingConfirmationId}` : ''}`
  }
  if (activatedDevice === 'pump') return '已联动开启喷药'
  if (activatedDevice === 'lamp') return '已联动开启驱虫灯'
  return null
}
