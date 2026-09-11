-- =====================================================================
-- 数智稻安 MySQL 初始化脚本（profile=mysql 时使用）
-- 执行：mysql -uroot -p < server/sql/init.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS rice_ultra
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE rice_ultra;

-- ---------------------------------------------------------------------
-- 登录账号（无注册功能，账号由管理员在库里维护）
-- 密码只保存 BCrypt 哈希（带 {bcrypt} 前缀的 Spring 委托格式），永不存明文
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_account (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  username        VARCHAR(64)  NOT NULL,
  password_hash   VARCHAR(128) NOT NULL,
  display_name    VARCHAR(64)  NOT NULL,
  role            VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
  enabled         BIT(1)       NOT NULL DEFAULT b'1',
  failed_attempts INT          NOT NULL DEFAULT 0,
  locked_until    DATETIME(6)  NULL,
  last_login_at   DATETIME(6)  NULL,
  created_at      DATETIME(6)  NOT NULL,
  updated_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- “记住密码”令牌：客户端持有 series.token，库里只存 token 的 SHA-256
-- 每次使用即轮换；series 对上但 token 不对 => 视为被盗，吊销该用户全部令牌
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS remember_me_token (
  series       VARCHAR(32)  NOT NULL,
  token_hash   VARCHAR(64)  NOT NULL,
  user_id      BIGINT       NOT NULL,
  user_agent   VARCHAR(255) NULL,
  created_at   DATETIME(6)  NOT NULL,
  last_used_at DATETIME(6)  NULL,
  expires_at   DATETIME(6)  NOT NULL,
  PRIMARY KEY (series),
  KEY idx_remember_me_token_user (user_id),
  CONSTRAINT fk_remember_me_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 历史环境数据由 Hive 查询，本脚本只管理登录和实时采集数据。

-- ---------------------------------------------------------------------
-- 当天实时传感器时序数据（由后续串口采集服务写入，不填充假数据）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS realtime_sensor_reading (
  id                          BIGINT       NOT NULL AUTO_INCREMENT,
  station_id                  VARCHAR(8)   NOT NULL,
  sampled_at                  DATETIME(6)  NOT NULL,
  light_klx                   DOUBLE       NULL,
  wind_speed_m_s              DOUBLE       NULL,
  rainfall_mm_h               DOUBLE       NULL,
  air_temperature_c           DOUBLE       NULL,
  air_humidity_percent        DOUBLE       NULL,
  soil_temperature_c          DOUBLE       NULL,
  soil_moisture_percent       DOUBLE       NULL,
  soil_nitrogen_mg_kg         DOUBLE       NULL,
  soil_phosphorus_mg_kg       DOUBLE       NULL,
  soil_potassium_mg_kg        DOUBLE       NULL,
  soil_ph                     DOUBLE       NULL,
  soil_ec_ms_cm               DOUBLE       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_realtime_station_sampled (station_id, sampled_at),
  KEY idx_realtime_station_time (station_id, sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 首次账号由服务启动时读取 conf/config.yaml 中 app.auth.bootstrap-admin.* 创建。
-- 示例默认不启用管理员初始化；请填写本机配置或使用 create-user.cmd。
-- 已有账号使用 create-user.cmd -Update 修改；这里仅建表，不预置固定密码。
-- ---------------------------------------------------------------------

-- Redis delivery queue; completed rows are removed after Redis acknowledgement.
CREATE TABLE IF NOT EXISTS redis_pending_sample (
  id VARCHAR(36) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  sampled_at DATETIME(6) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  payload VARCHAR(4096) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_redis_pending_time (sampled_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Global activity feed. Device-control history remains visible after server restart.
CREATE TABLE IF NOT EXISTS platform_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type VARCHAR(32) NOT NULL,
  message VARCHAR(2000) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  station_id VARCHAR(8) NULL,
  actor_username VARCHAR(64) NULL,
  actor_display_name VARCHAR(64) NULL,
  device VARCHAR(16) NULL,
  enabled BIT(1) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Read positions belong to each user; reading never removes another user's notification.
CREATE TABLE IF NOT EXISTS notification_read_state (
  user_id BIGINT NOT NULL,
  through_id BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_notification_read_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 田间巡检叶害 / 虫害每次识别结果；站点当前颜色由各站最新 leaf + 最新 pest 推导
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_diagnosis (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  station_id       VARCHAR(8)    NOT NULL,
  task             VARCHAR(16)   NOT NULL,
  filename         VARCHAR(255)  NULL,
  label            VARCHAR(128)  NULL,
  label_zh         VARCHAR(128)  NULL,
  confidence       DOUBLE        NULL,
  detection_count  INT           NOT NULL DEFAULT 0,
  alert_level      VARCHAR(16)   NOT NULL,
  result_json      TEXT          NOT NULL,
  created_at       DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_diagnosis_station_task_time (station_id, task, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 站点喷药(pump) / 驱虫灯(lamp) 开关；田间巡检联动与设备管理共用
-- 串口指令码仍为 FA01-FA04，不随界面文案变更
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS station_device (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  station_id  VARCHAR(8)   NOT NULL,
  device      VARCHAR(16)  NOT NULL,
  enabled     BIT(1)       NOT NULL DEFAULT b'0',
  updated_at  DATETIME(6)  NOT NULL,
  updated_by  VARCHAR(64)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_station_device (station_id, device)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
