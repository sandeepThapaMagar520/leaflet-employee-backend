ALTER TABLE users
    ADD COLUMN security_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN password_otp_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN password_otp_issued_at TIMESTAMP,
    ADD COLUMN password_otp_purpose VARCHAR(40),
    ADD COLUMN email_change_otp_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN email_change_otp_issued_at TIMESTAMP;

ALTER TABLE users
    ADD CONSTRAINT chk_users_security_version_positive CHECK (security_version > 0),
    ADD CONSTRAINT chk_users_password_otp_attempts_nonnegative CHECK (password_otp_failed_attempts >= 0),
    ADD CONSTRAINT chk_users_email_change_otp_attempts_nonnegative CHECK (email_change_otp_failed_attempts >= 0);

-- Existing bearer tokens and OTPs were stored under the legacy design. Invalidate
-- them during rollout rather than attempting to infer whether a value was raw or
-- hashed.
UPDATE users
SET password_otp = NULL,
    password_otp_expires_at = NULL,
    password_reset_token = NULL,
    password_reset_expires_at = NULL,
    email_verification_token = NULL,
    email_verification_expires_at = NULL,
    email_change_otp = NULL,
    email_change_otp_expires_at = NULL,
    password_otp_failed_attempts = 0,
    password_otp_issued_at = NULL,
    password_otp_purpose = NULL,
    email_change_otp_failed_attempts = 0,
    email_change_otp_issued_at = NULL;

ALTER TABLE users RENAME COLUMN password_otp TO password_otp_hash;
ALTER TABLE users RENAME COLUMN password_reset_token TO password_reset_token_hash;
ALTER TABLE users RENAME COLUMN email_verification_token TO email_verification_token_hash;
ALTER TABLE users RENAME COLUMN email_change_otp TO email_change_otp_hash;

ALTER INDEX IF EXISTS idx_users_password_reset_token RENAME TO idx_users_password_reset_token_hash;

CREATE TABLE auth_rate_limit_events (
    id BIGSERIAL PRIMARY KEY,
    rate_key VARCHAR(160) NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_auth_rate_limit_events_key_attempted
    ON auth_rate_limit_events(rate_key, attempted_at DESC);

CREATE TABLE security_audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    target_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    event_type VARCHAR(80) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    reason_code VARCHAR(80),
    details VARCHAR(500),
    account_identifier_hash VARCHAR(64),
    client_ip VARCHAR(64),
    user_agent VARCHAR(500),
    correlation_id VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_security_audit_events_target_created
    ON security_audit_events(target_user_id, created_at DESC);
CREATE INDEX idx_security_audit_events_type_created
    ON security_audit_events(event_type, created_at DESC);
CREATE INDEX idx_security_audit_events_correlation
    ON security_audit_events(correlation_id)
    WHERE correlation_id IS NOT NULL;
