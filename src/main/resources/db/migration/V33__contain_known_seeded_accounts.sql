-- Immediate containment for accounts created by V5, V6, V7, V9, and V12.
--
-- The seeded email AND a published seed password hash must still match. This
-- catches renamed seed accounts while preserving accounts that changed password.
WITH contained_accounts AS (
    UPDATE users
    SET active = FALSE,
        password = '{DISABLED_BY_V33_SECURITY_CONTAINMENT}',
        email_verified = FALSE,
        email_verification_token = NULL,
        email_verification_expires_at = NULL,
        password_otp = NULL,
        password_otp_expires_at = NULL,
        password_reset_token = NULL,
        password_reset_expires_at = NULL,
        pending_email = NULL,
        email_change_otp = NULL,
        email_change_otp_expires_at = NULL,
        must_change_password = TRUE,
        password_changed_at = CURRENT_TIMESTAMP
    WHERE email IN (
        'admin@example.com',
        'superadmin@ems.com',
        'employee@example.com',
        'sandeep@gmail.com',
        'sam@gmail.com'
    )
      AND password IN (
          '$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/zBsqquWbHqgV.1Vv8aR10Vq6V.',
          '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2'
      )
    RETURNING id
)
INSERT INTO staff_audit_events (
    staff_user_id,
    actor_user_id,
    action,
    description,
    created_at
)
SELECT
    id,
    NULL,
    'DEACTIVATED',
    'Account disabled by V33 security containment because it retained a published seed credential.',
    CURRENT_TIMESTAMP
FROM contained_accounts;
