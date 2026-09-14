-- Apply to the EXISTING application's MySQL database (select that database first).
-- Additive and idempotent; creates no account and changes no device permission.
--
-- Adds the password-login switch used by service accounts (for example the AstrBot bot).
-- Every existing account keeps logging in exactly as before: the column defaults to b'1',
-- the backfill below sets b'1' on every current row, and the application treats both b'1'
-- and NULL as "login allowed" -- only an explicit b'0' disables it.
--
-- A service account keeps enabled = b'1' so the server-side AstrBot identity mapping still
-- resolves it, while login_enabled = b'0' refuses passwords, remember-me and stale tokens.
--
-- MySQL 8 has no ADD COLUMN IF NOT EXISTS, so the column is added only when missing.
SET @ddl := (
  SELECT IF(COUNT(*) > 0,
    'SELECT ''user_account.login_enabled already present''',
    'ALTER TABLE user_account ADD COLUMN login_enabled BIT(1) NULL DEFAULT b''1'' AFTER enabled')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_account' AND COLUMN_NAME = 'login_enabled');
PREPARE apply_login_enabled FROM @ddl;
EXECUTE apply_login_enabled;
DEALLOCATE PREPARE apply_login_enabled;

-- Existing accounts stay exactly as they were.
UPDATE user_account SET login_enabled = b'1' WHERE login_enabled IS NULL;
