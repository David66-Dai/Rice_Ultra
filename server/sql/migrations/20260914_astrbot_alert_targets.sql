-- Apply to the EXISTING application's MySQL database (select that database first).
-- Additive and idempotent; creates no account and changes no device permission.
--
-- Per-session delivery state for prevention confirmations. One row per alert UMO, so a
-- WeChat failure can never block QQ (or the reverse), retries stay per target, and only a
-- session that actually received the alert may confirm it.
CREATE TABLE IF NOT EXISTS astrbot_diagnosis_confirmation_target (
  confirmation_id VARCHAR(36) NOT NULL,
  umo VARCHAR(255) NOT NULL,
  delivery_status VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  sent_at DATETIME(6) NULL,
  last_error VARCHAR(1000) NULL,
  PRIMARY KEY (confirmation_id, umo),
  KEY idx_astrbot_confirmation_target_status (confirmation_id, delivery_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- No backfill: which session a pre-upgrade confirmation actually reached is not recorded
-- anywhere, so this table starts empty and such a confirmation counts as delivered to no
-- session. That fails closed -- it can only refuse to start a device, never start one. Any
-- confirmation still inside its TTL at upgrade time should simply be re-triggered by running
-- the recognition again; the parent row's delivery_status is left untouched for the dashboard.
