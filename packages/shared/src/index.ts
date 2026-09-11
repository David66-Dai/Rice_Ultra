/** Shared API types for web / mobile clients. */

export type HealthResponse = {
  status: string;
  service: string;
  time?: string;
};

export type HistoryEnvironmentAverages = {
  lightKlx: number;
  windSpeedMs: number;
  rainfallMmH: number;
  airTemperatureC: number;
  airHumidityPercent: number;
  soilNitrogenMgKg: number;
  soilPhosphorusMgKg: number;
  soilPotassiumMgKg: number;
  soilPh: number;
  soilEcMsCm: number;
};

export type HistoryPestDiseaseArchive = {
  diseaseCount: number;
  pestDensityPer100Plants: number;
  affectedAreaPercent: number;
  riskIndex: number;
  recognitionConfidencePercent: number;
};

export type HistorySpectralArchive = {
  ndvi: number;
  ndre: number;
  gndvi: number;
  chlorophyllSpad: number;
  reflectancePercent: number[];
};

export type HistoryDayData = {
  date: string;
  stationId: string;
  environment: HistoryEnvironmentAverages;
  pestDisease: HistoryPestDiseaseArchive;
  spectrum: HistorySpectralArchive;
};

export type HistoryDailyResponse = {
  current: HistoryDayData;
  previous: HistoryDayData | null;
};

export type HistoryRangeResponse = {
  stationId: string;
  startDate: string;
  endDate: string;
  recordCount: number;
};

export type RealtimeSensorReading = {
  sampledAt: string;
  lightKlx: number | null;
  windSpeedMs: number | null;
  rainfallMmH: number | null;
  airTemperatureC: number | null;
  airHumidityPercent: number | null;
  soilNitrogenMgKg: number | null;
  soilPhosphorusMgKg: number | null;
  soilPotassiumMgKg: number | null;
  soilPh: number | null;
  soilEcMsCm: number | null;
};

export type RealtimeTodayResponse = {
  stationId: string;
  date: string;
  readings: RealtimeSensorReading[];
};

export type DeviceControlRequest = {
  stationId: string;
  device: "pump" | "lamp";
  enabled: boolean;
};

export type DeviceControlResponse = DeviceControlRequest & {
  command: string;
  sentAt: string;
};

export type LeafDiagnosisResult = {
  task: "leaf";
  filename?: string;
  label: string;
  label_zh?: string;
  confidence: number;
  note?: string;
};

export type PestDetection = {
  class_name: string;
  confidence: number;
  bbox?: [number, number, number, number];
};

export type PestDiagnosisResult = {
  task: "pest";
  filename?: string;
  detections: PestDetection[];
  note?: string;
};

/* ---------- 登录 / 鉴权 ---------- */

/** POST /api/auth/login */
export type LoginRequest = {
  username: string;
  password: string;
  /** 勾选“记住密码”：服务端额外下发长期 rememberToken（不保存明文密码） */
  rememberMe: boolean;
};

/** POST /api/auth/remember */
export type RememberRequest = {
  rememberToken: string;
};

/** POST /api/auth/logout */
export type LogoutRequest = {
  rememberToken?: string | null;
};

export type UserInfo = {
  id: number;
  username: string;
  displayName: string;
  role: string;
  lastLoginAt?: string | null;
};

/** login / remember 的统一响应 */
export type LoginResponse = {
  tokenType: "Bearer";
  accessToken: string;
  /** 访问令牌有效秒数 */
  expiresIn: number;
  /** 仅 rememberMe 时返回；每次使用都会轮换，客户端需覆盖保存 */
  rememberToken: string | null;
  user: UserInfo;
};

/** 服务端统一错误体 */
export type ApiErrorBody = {
  code: string;
  message: string;
  timestamp?: string;
};

/** Default local Java API base (Emulator / device may need LAN IP). */
export const DEFAULT_API_BASE = "http://127.0.0.1:8080";
