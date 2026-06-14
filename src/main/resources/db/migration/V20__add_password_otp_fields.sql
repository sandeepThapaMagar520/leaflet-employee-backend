ALTER TABLE users ADD COLUMN IF NOT EXISTS password_otp VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_otp_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_expires_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_password_reset_token
    ON users (password_reset_token)
    WHERE password_reset_token IS NOT NULL;
