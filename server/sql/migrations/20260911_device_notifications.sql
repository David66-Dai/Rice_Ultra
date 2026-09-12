-- Apply to the EXISTING application's MySQL database (select that database first).
-- Additive and idempotent; does not create accounts or change device permissions.
CREATE TABLE IF NOT EXISTS station_device (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  station_id  VARCHAR(8)   NOT NULL,
  device      VARCHAR(16)  NOT NULL,
  enabled     BIT(1)       NULL,
  revision    BIGINT       NOT NULL DEFAULT 0,
  updated_at  DATETIME(6)  NOT NULL,
  updated_by  VARCHAR(64)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_station_device (station_id, device)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE station_device MODIFY COLUMN enabled BIT(1) NULL;
ALTER TABLE station_device ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;

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

CREATE TABLE IF NOT EXISTS notification_read_state (
  user_id BIGINT NOT NULL,
  through_id BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_notification_read_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS astrbot_scheduled_stop (
  request_id VARCHAR(36) NOT NULL,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(64) NULL,
  station_id VARCHAR(8) NOT NULL,
  device VARCHAR(16) NOT NULL,
  expected_revision BIGINT NOT NULL,
  due_at DATETIME(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  PRIMARY KEY (request_id),
  KEY idx_astrbot_stop_status_due (status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS astrbot_scheduled_stop (
  request_id VARCHAR(36) NOT NULL,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(64) NULL,
  station_id VARCHAR(8) NOT NULL,
  device VARCHAR(16) NOT NULL,
  expected_revision BIGINT NOT NULL,
  due_at DATETIME(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  PRIMARY KEY (request_id),
  KEY idx_astrbot_stop_status_due (status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
