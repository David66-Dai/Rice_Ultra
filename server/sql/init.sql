-- =====================================================================
-- 数智稻安 MySQL 初始化脚本（profile=mysql 时使用）
-- 执行：mysql -uroot -p < server/sql/init.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS smart_rice_security
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE smart_rice_security;

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

-- ---------------------------------------------------------------------
-- 历史日数据：环境/土壤日均值、病虫害识别与多光谱归档
-- record_date + station_id 唯一，保证导入脚本可安全重复执行
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS historical_daily_data (
  id                              BIGINT       NOT NULL AUTO_INCREMENT,
  record_date                     DATE         NOT NULL,
  station_id                      VARCHAR(8)   NOT NULL,
  avg_light_klx                   DOUBLE       NOT NULL,
  avg_wind_speed_m_s              DOUBLE       NOT NULL,
  avg_rainfall_mm_h               DOUBLE       NOT NULL,
  avg_air_temperature_c           DOUBLE       NOT NULL,
  avg_air_humidity_percent        DOUBLE       NOT NULL,
  avg_soil_nitrogen_mg_kg         DOUBLE       NOT NULL,
  avg_soil_phosphorus_mg_kg       DOUBLE       NOT NULL,
  avg_soil_potassium_mg_kg        DOUBLE       NOT NULL,
  avg_soil_ph                     DOUBLE       NOT NULL,
  avg_soil_ec_ms_cm               DOUBLE       NOT NULL,
  disease_count                   INT          NOT NULL,
  pest_density_per_100_plants     DOUBLE       NOT NULL,
  affected_area_percent           DOUBLE       NOT NULL,
  pest_disease_risk_index         DOUBLE       NOT NULL,
  recognition_confidence_percent  DOUBLE       NOT NULL,
  ndvi                            DOUBLE       NOT NULL,
  ndre                            DOUBLE       NOT NULL,
  gndvi                           DOUBLE       NOT NULL,
  chlorophyll_spad                DOUBLE       NOT NULL,
  reflectance_450nm_percent       DOUBLE       NOT NULL,
  reflectance_550nm_percent       DOUBLE       NOT NULL,
  reflectance_650nm_percent       DOUBLE       NOT NULL,
  reflectance_720nm_percent       DOUBLE       NOT NULL,
  reflectance_800nm_percent       DOUBLE       NOT NULL,
  reflectance_900nm_percent       DOUBLE       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_history_date_station (record_date, station_id),
  KEY idx_history_station_date (station_id, record_date),
  KEY idx_history_record_date (record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
-- 初始管理员：admin / SmartRice@2026（首次登录后请立刻修改）
-- 服务启动时若用户表为空也会按 application.properties 中
-- app.auth.bootstrap-admin.* 自动创建，这里的 INSERT 可按需保留或删除。
-- 自行生成哈希：new BCryptPasswordEncoder().encode("新密码")，前面加 {bcrypt}
-- ---------------------------------------------------------------------
INSERT INTO user_account (username, password_hash, display_name, role, enabled, failed_attempts, created_at, updated_at)
SELECT 'admin',
       '{bcrypt}$2a$10$ep3aPAlMBDHWOro.VMCFo.HZAjLQh8DYtM0tRwtz1VRVDdLt6UJ0u',
       '系统管理员', 'ADMIN', b'1', 0, NOW(6), NOW(6)
WHERE NOT EXISTS (SELECT 1 FROM user_account WHERE username = 'admin');
