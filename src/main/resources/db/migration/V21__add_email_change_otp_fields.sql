ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_change_otp VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_change_otp_expires_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_pending_email
    ON users (pending_email)
    WHERE pending_email IS NOT NULL;
