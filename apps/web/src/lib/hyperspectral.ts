export type HyperspectralCurve = {
  polyline: string
  label: string
  detail: string
  confidence: number
  ariaLabel: string
}

export type SpectrumPayload = {
  wavelengths?: number[] | null
  reflectance?: number[] | null
  ndvi?: number | null
  ndre?: number | null
}

export type HyperspectralPrediction = {
  label?: string | null
  labelZh?: string | null
  confidence?: number | null
  severity?: number | null
  hasDamage?: boolean | null
  spectrum?: SpectrumPayload | null
}

function riceLeafReflectance(wavelengthNm: number) {
  if (wavelengthNm < 500) return 0.05 + 0.04 * ((wavelengthNm - 400) / 100)
  if (wavelengthNm < 580) return 0.09 + 0.13 * Math.sin(((wavelengthNm - 500) / 80) * Math.PI)
  if (wavelengthNm < 700) return 0.18 - 0.12 * ((wavelengthNm - 580) / 120)
  if (wavelengthNm < 760) return 0.06 + 0.46 * ((wavelengthNm - 700) / 60)
  return 0.5 + 0.05 * Math.sin(((wavelengthNm - 760) / 240) * Math.PI)
}

export function spectrumToPolyline(wavelengths: number[], reflectance: number[], width = 240, height = 40) {
  if (!wavelengths.length || wavelengths.length !== reflectance.length) return ''
  const minWl = wavelengths[0]
  const span = Math.max(wavelengths[wavelengths.length - 1] - minWl, 1)
  const minR = Math.min(...reflectance)
  const maxR = Math.max(...reflectance)
  const range = Math.max(maxR - minR, 0.04)
  return wavelengths.map((wavelength, index) => {
    const x = ((wavelength - minWl) / span) * width
    const y = height - 4 - ((reflectance[index] - minR) / range) * (height - 8)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

export function confidencePercent(value: number | null | undefined) {
  if (value == null || Number.isNaN(value)) return 0
  return Math.round(value <= 1 ? value * 1000 : value * 10) / 10
}

export function buildHyperspectralReading(
  online: boolean,
  prediction?: HyperspectralPrediction | null,
): HyperspectralCurve {
  const spectrum = prediction?.spectrum
  const wavelengths = spectrum?.wavelengths?.filter((item): item is number => typeof item === 'number') ?? []
  const reflectance = spectrum?.reflectance?.filter((item): item is number => typeof item === 'number') ?? []
  const hasCube = wavelengths.length > 1 && wavelengths.length === reflectance.length
  const polyline = hasCube
    ? spectrumToPolyline(wavelengths, reflectance)
    : spectrumToPolyline(
      Array.from({ length: 36 }, (_, index) => 400 + (index / 35) * 600),
      Array.from({ length: 36 }, (_, index) => {
        const wavelength = 400 + (index / 35) * 600
        return online ? riceLeafReflectance(wavelength) : 0.08
      }),
    )
  if (!online && !hasCube) {
    return {
      polyline,
      label: '暂无高光谱立方体',
      detail: '仅在线站点可上传 VIS–NIR 立方体',
      confidence: 0,
      ariaLabel: '高光谱设备离线',
    }
  }
  if (hasCube) {
    const label = prediction?.labelZh || prediction?.label || '已识别'
    const confidence = confidencePercent(prediction?.confidence)
    const ndvi = spectrum?.ndvi
    const ndre = spectrum?.ndre
    const severity = prediction?.severity
    const indices = [
      ndvi != null ? `NDVI ${ndvi.toFixed(2)}` : null,
      ndre != null ? `NDRE ${ndre.toFixed(2)}` : null,
      severity != null ? `严重度 ${severity.toFixed(1)}` : null,
      confidence ? `置信度 ${confidence}%` : null,
    ].filter(Boolean)
    return {
      polyline,
      label,
      detail: `${wavelengths.length} 波段 · 400–1000 nm${indices.length ? ` · ${indices.join(' · ')}` : ''}`,
      confidence,
      ariaLabel: `高光谱反射率曲线，识别为${label}`,
    }
  }
  return {
    polyline,
    label: '等待上传立方体',
    detail: '核心识别 · .h5 / .zip · 1D-CNN · 点击右侧光环上传',
    confidence: 0,
    ariaLabel: '尚未上传高光谱立方体',
  }
}
