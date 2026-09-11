import type { StationAlertLevel } from '@smart-rice-security/shared'

export const ONLINE_STATION_ID = 'S01'

export const STATION_ALERTS_CHANGED = 'smart-rice-station-alerts-changed'

export type StationVisualLevel = 'normal' | 'attention' | 'danger' | 'offline'

export function canDiagnoseStation(stationId: string): boolean {
  return stationId === ONLINE_STATION_ID
}

export function emitStationAlertsChanged() {
  window.dispatchEvent(new Event(STATION_ALERTS_CHANGED))
}

export function stationVisualLevel(online: boolean, alert: StationAlertLevel = 'green'): StationVisualLevel {
  if (alert === 'red') return 'danger'
  if (alert === 'yellow') return 'attention'
  return online ? 'normal' : 'offline'
}

export function stationLevelLabel(online: boolean, alert: StationAlertLevel = 'green'): string {
  if (alert === 'red') return '红色告警'
  if (alert === 'yellow') return '黄色预警'
  return online ? '在线' : '离线'
}

export function stationPointClass(online: boolean, alert: StationAlertLevel | undefined, selected: boolean): string {
  const parts = ['station-point', online ? 'is-online' : 'is-offline']
  if (alert === 'red') parts.push('is-alert-red')
  else if (alert === 'yellow') parts.push('is-alert-yellow')
  if (selected) parts.push('is-selected')
  return parts.join(' ')
}

export function recognitionCardLevel(online: boolean, alert: StationAlertLevel | undefined): StationVisualLevel {
  if (!online) return 'offline'
  if (alert === 'red') return 'danger'
  if (alert === 'yellow') return 'attention'
  return 'normal'
}

export function formatConfidence(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return ''
  const percent = value <= 1 ? value * 100 : value
  return `${percent.toFixed(1)}%`
}

export function confidencePercent(value: number | null | undefined): number {
  if (value == null || Number.isNaN(value)) return 0
  const percent = value <= 1 ? value * 100 : value
  return Math.max(0, Math.min(100, percent))
}
