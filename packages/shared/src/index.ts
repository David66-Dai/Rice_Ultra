/** Shared API types for web / mobile clients. */

export type HealthResponse = {
  status: string;
  service: string;
  time?: string;
};

export type HistoryEnvironmentAverages = {
  lightKlx: number | null;
  windSpeedMs: number | null;
  rainfallMmH: number | null;
  airTemperatureC: number | null;
  airHumidityPercent: number | null;
  soilTemperatureC: number | null;
  soilMoisturePercent: number | null;
  soilNitrogenPpm: number | null;
  soilPhosphorusPpm: number | null;
  soilPotassiumPpm: number | null;
  soilPh: number | null;
  soilEcMsCm: number | null;
};

/** Fixed catalogue of three diseases and three pests; see PEST_DISEASE_CATALOG on the web client. */
export type PestDiseaseCategory = "disease" | "pest";
export type PestDiseaseAlertLevel = "green" | "yellow" | "red";

export type PestDiseaseItem = {
  key: string;
  category: PestDiseaseCategory;
  label: string;
  /** 次 for a disease (recognitions that day), 只 for a pest (insects detected). */
  unit: string;
  value: number | null;
  alertLevel: PestDiseaseAlertLevel;
};

/**
 * One day of pest and disease figures. `source` is "inspection_diagnosis" for the current field day,
 * "none" for any earlier day the backend has no records for, and "mock" once the web client has
 * filled an earlier day in from its own sample data.
 */
export type PestDiseaseSummary = {
  available: boolean;
  source: string;
  sourceTable: string;
  stationId: string;
  referenceDate: string;
  lastDiagnosedAt: string | null;
  recognitionCount: number;
  items: PestDiseaseItem[];
  notes: string[];
};

export type HistorySpectralArchive = {
  ndvi: number | null;
  ndre: number | null;
  gndvi: number | null;
  chlorophyllSpad: number | null;
  reflectancePercent: (number | null)[] | null;
};

export type HistoryDayData = {
  date: string;
  stationId: string;
  environment: HistoryEnvironmentAverages;
  pestDisease: PestDiseaseSummary | null;
  spectrum: HistorySpectralArchive | null;
  source?: "hive";
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
  expectedRevision?: number;
};

export type DeviceControlResponse = {
  stationId: string;
  device: "pump" | "lamp";
  enabled: boolean;
  command: string;
  sentAt: string;
  state?: DeviceState;
};

export type DeviceStatusResponse = {
  stationId: string;
  pump: boolean;
  lamp: boolean;
  updatedAt: string | null;
};

/** Last successfully sent command, not physical actuator feedback. */
export type DeviceState = {
  stationId: string;
  device: "pump" | "lamp";
  enabled: boolean | null;
  revision: number;
  updatedAt: string | null;
  updatedBy: string | null;
};

export type PreventionPolicyState = {
  requireAstrBotConfirmation: boolean;
  revision: number;
  updatedAt: string;
  updatedBy: string | null;
  spraySafetyEnabled: boolean;
  maxSprayWindSpeedMs: number;
  sensorMaxAgeSeconds: number;
  leafEvidenceMaxAgeSeconds: number;
};

export type PreventionPolicyUpdateRequest = {
  requireAstrBotConfirmation: boolean;
  expectedRevision: number;
};

export type PlatformNotification = {
  id: number;
  type: "device_control" | "pest_disease";
  message: string;
  createdAt: string;
  stationId: string | null;
  actorUsername: string | null;
  actorDisplayName: string | null;
  device: "pump" | "lamp" | null;
  enabled: boolean | null;
};

export type DeviceSyncResponse = {
  cursor: string;
  canControl: boolean;
  available: boolean;
  devices: DeviceState[];
  preventionPolicy: PreventionPolicyState;
  notifications: PlatformNotification[];
  unreadCount: number;
};

export type LeafDiagnosisResult = {
  task: "leaf";
  filename?: string;
  label: string;
  label_zh?: string;
  confidence: number;
  has_leaf_damage?: boolean;
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
  count?: number;
  note?: string;
};

export type StationAlertLevel = "green" | "yellow" | "red";

export type LeafHsiDiagnosisResult = {
  task: "leaf-hsi";
  filename?: string | null;
  label: string;
  label_zh?: string;
  confidence: number;
  has_leaf_damage?: boolean;
  severity?: number;
  alert_level?: StationAlertLevel;
  all_probabilities?: Record<string, number>;
  spectrum?: {
    wavelengths?: number[];
    reflectance?: number[];
    ndvi?: number;
    ndre?: number;
  };
};

export type DiagnosisTask = "leaf" | "pest";

export type DiagnosisRecord = {
  id: number;
  stationId: string;
  task: DiagnosisTask;
  filename?: string | null;
  label: string | null;
  labelZh: string | null;
  confidence: number | null;
  detectionCount: number;
  alertLevel: StationAlertLevel;
  stationAlertLevel: StationAlertLevel;
  createdAt: string;
  result: LeafDiagnosisResult | PestDiagnosisResult | LeafHsiDiagnosisResult;
  activatedDevice?: "pump" | "lamp" | null;
  deviceError?: string | null;
  confirmationRequired?: boolean;
  pendingConfirmationId?: string | null;
  // PARTIAL: some alert sessions received the request and some did not; only a session
  // that actually received it may confirm, so the device still stays closed until then.
  alertDeliveryStatus?: "PENDING" | "SENT" | "PARTIAL" | "FAILED" | null;
};

export type StationAlertStatus = {
  stationId: string;
  alertLevel: StationAlertLevel;
  leafAlertLevel: StationAlertLevel;
  leafLabel: string | null;
  leafLabelZh: string | null;
  leafConfidence: number | null;
  pestAlertLevel: StationAlertLevel;
  pestCount: number | null;
  pestLabel: string | null;
  updatedAt: string | null;
};

export type StationAlertListResponse = {
  stations: StationAlertStatus[];
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

/* ---------- 基于历史监测证据的 AI 分析 ---------- */
export type AiGrowthStage = "unknown" | "seedling" | "tillering" | "jointing" | "booting" | "heading" | "filling" | "mature";
export type AiWindowDays = 7 | 14 | 30;
export type AiAnalysisRequest = {
  stationId: string;
  date: string;
  windowDays: AiWindowDays;
  growthStage: AiGrowthStage;
};
export type AiEvidenceMetric = {
  field: string;
  label: string;
  unit: string;
  count: number;
  missingCount: number;
  mean: number | null;
  min: number | null;
  max: number | null;
  first: number | null;
  last: number | null;
  change: number | null;
};
export type AiEvidence = {
  stationId: string;
  hiveStation: string;
  startDate: string;
  endDate: string;
  windowDays: AiWindowDays;
  observedDays: number;
  missingDates: string[];
  rawRowCount: number;
  metrics: AiEvidenceMetric[];
  daily: { date: string; values: Record<string, number | null> }[];
  /** Effective 生长周期; read from farm.env_daily when that day recorded one. */
  growthStage: string;
  growthStageLabel: string | null;
  growthStageSource: "hive" | "user" | "unknown";
  limitations: string[];
  legacyContext?: AiLegacyContext;
};
export type AiAnalysisResult = {
  evidence: AiEvidence;
  weatherAnalysis: string;
  soilAnalysis: string;
  riskAnalysis: string;
  summary: string;
  workflowRunId: string;
};
export type AiAnalysisJob = {
  id: string;
  stationId: string;
  date: string;
  windowDays: AiWindowDays;
  status: "queued" | "running" | "succeeded" | "failed";
  createdAt: string;
  completedAt: string | null;
  error: string | null;
  result: AiAnalysisResult | null;
  generatedAt: string | null;
  expiresAt: string | null;
  expired: boolean;
  archiveId: string | null;
  archivePath: string | null;
};
export type AiAnalysisListResponse = AiAnalysisJob[];
export type AiStatusResponse = { configured: boolean; storageConfigured?: boolean };

export type AiLegacyContext = {
  source: string;
  stationId: string;
  pestDisease: PestDiseaseSummary;
  yield: {
    available: boolean;
    sourceTable: string;
    referenceYear: number | null;
    season: string;
    matchType: "exact_year" | "latest_prior" | "missing";
    baselineKgPerMu: number | null;
  };
  limitations: string[];
};
