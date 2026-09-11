export type LiveWeather = {
  city: string
  temperature: number
  weather: string
  weatherIcon: string
  airQuality: string
  aqi: number
}

type IpLocationResponse = {
  success?: boolean
  city?: string
  region?: string
  latitude?: number
  longitude?: number
}

type ReverseLocationResponse = {
  city?: string
  locality?: string
  principalSubdivision?: string
}

type WeatherResponse = {
  current?: {
    temperature_2m?: number
    weather_code?: number
  }
}

type AirQualityResponse = {
  current?: {
    us_aqi?: number
  }
}

const WEATHER_CODES: Record<number, { text: string; icon: string }> = {
  0: { text: '晴', icon: '☀' },
  1: { text: '晴间多云', icon: '🌤' },
  2: { text: '多云', icon: '⛅' },
  3: { text: '阴', icon: '☁' },
  45: { text: '雾', icon: '🌫' },
  48: { text: '雾凇', icon: '🌫' },
  51: { text: '小毛毛雨', icon: '🌦' },
  53: { text: '毛毛雨', icon: '🌦' },
  55: { text: '强毛毛雨', icon: '🌧' },
  56: { text: '冻毛毛雨', icon: '🌧' },
  57: { text: '强冻毛毛雨', icon: '🌧' },
  61: { text: '小雨', icon: '🌦' },
  63: { text: '中雨', icon: '🌧' },
  65: { text: '大雨', icon: '🌧' },
  66: { text: '冻雨', icon: '🌧' },
  67: { text: '强冻雨', icon: '🌧' },
  71: { text: '小雪', icon: '🌨' },
  73: { text: '中雪', icon: '🌨' },
  75: { text: '大雪', icon: '❄' },
  77: { text: '米雪', icon: '🌨' },
  80: { text: '小阵雨', icon: '🌦' },
  81: { text: '中阵雨', icon: '🌧' },
  82: { text: '强阵雨', icon: '⛈' },
  85: { text: '小阵雪', icon: '🌨' },
  86: { text: '强阵雪', icon: '❄' },
  95: { text: '雷阵雨', icon: '⛈' },
  96: { text: '雷雨伴冰雹', icon: '⛈' },
  99: { text: '强雷雨伴冰雹', icon: '⛈' },
}

function airQualityLabel(aqi: number): string {
  if (aqi <= 50) return '优'
  if (aqi <= 100) return '良'
  if (aqi <= 150) return '轻度污染'
  if (aqi <= 200) return '中度污染'
  if (aqi <= 300) return '重度污染'
  return '严重污染'
}

async function getJson<T>(url: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`天气服务请求失败（${response.status}）`)
  return response.json() as Promise<T>
}

function getBrowserPosition(): Promise<{ latitude: number; longitude: number }> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('当前浏览器不支持定位'))
      return
    }
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => resolve({ latitude: coords.latitude, longitude: coords.longitude }),
      () => reject(new Error('未获得定位权限')),
      { enableHighAccuracy: false, timeout: 6000, maximumAge: 10 * 60 * 1000 },
    )
  })
}

async function resolveLocation(signal: AbortSignal): Promise<{
  city: string
  latitude: number
  longitude: number
}> {
  try {
    // 优先浏览器位置：开代理时也不会把代理服务器所在地误当成当前城市
    const position = await getBrowserPosition()
    const reverse = await getJson<ReverseLocationResponse>(
      `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${encodeURIComponent(position.latitude)}&longitude=${encodeURIComponent(position.longitude)}&localityLanguage=zh`,
      signal,
    )
    return {
      ...position,
      city: reverse.city || reverse.locality || reverse.principalSubdivision || '当前位置',
    }
  } catch {
    // 用户拒绝定位、浏览器不支持或反向地理接口失败时，以公网 IP 粗略定位
    const location = await getJson<IpLocationResponse>('https://ipwho.is/?lang=zh', signal)
    if (location.success === false || location.latitude == null || location.longitude == null) {
      throw new Error('无法获取当前城市')
    }
    return {
      city: location.city || location.region || '当前位置',
      latitude: location.latitude,
      longitude: location.longitude,
    }
  }
}

/**
 * 优先通过浏览器定位获取当前城市；拒绝权限时回退到公网 IP 粗略定位。
 * 天气与空气质量来自 Open-Meteo，所有接口均不需要密钥。
 */
export async function loadLiveWeather(signal: AbortSignal): Promise<LiveWeather> {
  const location = await resolveLocation(signal)
  const { latitude, longitude } = location

  const coordinate = `latitude=${encodeURIComponent(latitude)}&longitude=${encodeURIComponent(longitude)}`
  const [weatherData, airData] = await Promise.all([
    getJson<WeatherResponse>(
      `https://api.open-meteo.com/v1/forecast?${coordinate}&current=temperature_2m,weather_code&timezone=auto`,
      signal,
    ),
    getJson<AirQualityResponse>(
      `https://air-quality-api.open-meteo.com/v1/air-quality?${coordinate}&current=us_aqi&timezone=auto`,
      signal,
    ),
  ])

  const temperature = weatherData.current?.temperature_2m
  const code = weatherData.current?.weather_code
  const aqi = airData.current?.us_aqi
  if (temperature == null || code == null || aqi == null) {
    throw new Error('实时天气数据不完整')
  }

  const condition = WEATHER_CODES[code] ?? { text: '未知天气', icon: '◌' }
  return {
    city: location.city,
    temperature,
    weather: condition.text,
    weatherIcon: condition.icon,
    airQuality: airQualityLabel(aqi),
    aqi: Math.round(aqi),
  }
}
