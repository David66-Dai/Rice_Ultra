import { useCallback, useEffect, useMemo, useState } from 'react'
import type { StationAlertListResponse, StationAlertStatus } from '@smart-rice-security/shared'
import { useAuth } from '../auth/useAuth'
import { STATION_ALERTS_CHANGED } from '../lib/station-alerts'

export function useStationAlerts() {
  const auth = useAuth()
  const [stations, setStations] = useState<StationAlertStatus[]>([])

  const refresh = useCallback(() => (
    auth.request<StationAlertListResponse>('/api/diagnosis/stations')
      .then((body) => setStations(body.stations ?? []))
      .catch(() => undefined)
  ), [auth])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => { void refresh() }, 8_000)
    const onChange = () => { void refresh() }
    window.addEventListener(STATION_ALERTS_CHANGED, onChange)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener(STATION_ALERTS_CHANGED, onChange)
    }
  }, [refresh])

  const byId = useMemo(
    () => Object.fromEntries(stations.map((item) => [item.stationId, item])) as Record<string, StationAlertStatus>,
    [stations],
  )

  return { stations, byId, refresh }
}
