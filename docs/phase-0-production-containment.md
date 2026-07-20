# Phase 0 production containment

## Immediate incident response

Perform these actions before or alongside deploying V33:

1. Disable `admin@example.com`, `superadmin@ems.com`, `employee@example.com`,
   `sandeep@gmail.com`, and `sam@gmail.com` immediately if they still use the
   published seed credential.
2. Rotate every legitimate administrator password.
3. Generate and deploy a new high-entropy `JWT_SECRET`. This invalidates all
   JWTs signed with the old key.
4. Treat all existing sessions as untrusted and require users to sign in again.
5. Inspect Render and application authentication logs for successful or
   repeated attempts involving the seeded email addresses.
6. Back up Supabase and confirm the backup can be restored.
7. Verify `flyway_schema_history` shows V33 as successful after deployment.
8. Confirm each affected account is inactive, its recovery fields are null,
   and a matching `staff_audit_events` row exists.

Do not edit or repair V5, V6, V7, V9, V12, or V18. V33 is the forward-only
containment migration.

## Seeded-account history reviewed

- V5 created `admin@example.com` with a published BCrypt credential.
- V6 changed that administrator to the BCrypt hash documented as `password`.
- V7 created `superadmin@ems.com` with the same known password.
- V9 created `employee@example.com` with the same known password.
- V12 created `sandeep@gmail.com` and `sam@gmail.com` with the same known
  password.
- V18 marked all existing users, including these seeds, as email verified while
  adding profile and account-state fields.

V33 requires the seeded email and one of the two published hashes to match. This
still catches a renamed seed account. A user who has changed their password is
preserved, and an unrelated account is preserved even if it happens to use the
same hash.

## Required production configuration

Set `APP_ENVIRONMENT=production`. Production startup is refused unless all of
the following are explicitly configured with non-placeholder values:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET` (at least 32 characters)
- `ALLOWED_ORIGIN_PATTERNS` (explicit HTTPS origins only)
- `MAIL_ENABLED=true`
- `MAIL_PROVIDER=GOOGLE_APPS_SCRIPT`
- `GOOGLE_MAIL_WEBHOOK_URL`
- `GOOGLE_MAIL_WEBHOOK_SECRET` (at least 24 characters)
- `FRONTEND_BASE_URL` (HTTPS)
- `CLOUDINARY_CLOUD_NAME`
- `CLOUDINARY_UPLOAD_PRESET`

Local and test environments are not subject to the production validator, but
must still supply an explicit JWT secret when authentication is exercised.

## One-time emergency administrator

The bootstrap is disabled by default. Use it only when no legitimate
administrator can recover access.

1. Choose a new email address that does not already exist.
2. Configure:

   ```text
   EMERGENCY_ADMIN_BOOTSTRAP_ENABLED=true
   EMERGENCY_ADMIN_BOOTSTRAP_EMAIL=<new recovery administrator email>
   EMERGENCY_ADMIN_BOOTSTRAP_FULL_NAME=<administrator name>
   EMERGENCY_ADMIN_BOOTSTRAP_PASSWORD=<unique 16+ character one-time password>
   ```

3. Deploy once and verify the log reports completion without printing the
   email or password.
4. Immediately remove all four variables and redeploy.
5. Complete first-time account setup. The account is created unverified with
   `must_change_password=true`.

The durable app-setting
`security.emergency-admin-bootstrap.completed=true` prevents the bootstrap
from being consumed more than once. It never elevates or overwrites an existing
account. Resetting this marker is a security-sensitive database operation that
requires a separately approved incident-response change.

## Verification queries

Run read-only queries after deployment:

```sql
SELECT email, active, must_change_password, password_reset_token, password_otp
FROM users
WHERE email IN (
  'admin@example.com',
  'superadmin@ems.com',
  'employee@example.com',
  'sandeep@gmail.com',
  'sam@gmail.com'
);

SELECT staff_user_id, action, description, created_at
FROM staff_audit_events
WHERE description LIKE 'Account disabled by V33 security containment%';

SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version = '33';
```

## Rollback

Do not roll back by re-enabling the seeded accounts or restoring their known
password hashes. If V33 disables an account that is later verified as
legitimate, use the normal administrator recovery flow to assign a new unique
temporary credential, retain the audit trail, and force first-time setup.
