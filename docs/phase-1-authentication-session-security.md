# Phase 1 authentication and session security

## Scope and flow inventory

This phase changes authentication, OTP, recovery-token, session-revocation,
security-audit, and corresponding frontend session handling only. It preserves
the V33 containment migration and does not change manager scope, uploads,
leave/attendance rules, or unrelated project services.

Public authentication endpoints are:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/start-account-setup`
- `POST /api/v1/auth/forgot-password`
- `POST /api/v1/auth/verify-password-otp`
- `POST /api/v1/auth/set-password`
- `POST /api/v1/auth/verify-email`

`POST /api/v1/auth/register` is controller-protected for administrators.
Password change, email change, verification resend, and session revocation
require an authenticated employee, manager, or administrator. Administrative
revocation requires the administrator role.

The normal authenticated request path is:

1. The frontend reads the access token from tab-scoped `sessionStorage`.
2. It sends `Authorization: Bearer <token>` to the API.
3. the JWT filter validates signature, expiration, issuer, and audience.
4. It requires the `sv` security-version claim and loads the subject from
   PostgreSQL.
5. It rejects missing, disabled, or version-mismatched users and derives the
   authority from the current database role.
6. Spring method authorization runs before the controller/service operation.

Normal login verifies the password before disclosing first-time-setup state,
records success/failure, updates `last_login_at`, and returns a new access token.
Account setup first validates the temporary credential, creates one hashed OTP
challenge transactionally, commits, and then attempts delivery. OTP verification
atomically consumes the challenge and creates a hashed short-lived reset token.
Password recovery uses the same challenge engine but returns a generic request
response for both known and unknown accounts. Password reset atomically updates
the password, consumes the reset token, increments `security_version`, and writes
the required audit event.

Authenticated password change locks the user row and increments
`security_version`. Email verification bearer tokens are random and stored only
as SHA-256 hashes; successful verification consumes the token in the same
transaction. Email-change OTPs are BCrypt hashes and a successful identity
change increments `security_version`.

Ordinary logout only clears the browser's current access token. It does not
revoke a copied token. “Sign out everywhere” calls
`POST /api/v1/users/me/sessions/revoke-all`, then clears browser state.
Administrators use `POST /api/v1/users/{id}/sessions/revoke-all` with an optional
reason. Both actions lock the target row, increment its version, and audit actor,
target, outcome, request metadata, and correlation identifier.

The browser idle timer is a client-side convenience, not server-side revocation.
“Continue Session” resets only that idle timer and explicitly does not extend the
JWT. Browser closure removes `sessionStorage`. Attendance can suppress the
client-side idle logout under the existing product policy, but cannot extend an
expired JWT. Any protected API `401` clears browser auth state and redirects to
login. A password change also clears the current browser token because that
operation revokes all previously issued access tokens.

## JWT policy

New JWTs contain `sub`, `iat`, `exp`, `iss`, `aud`, `jti`, and `sv`, with a
signing-key identifier in the header. Database role remains authoritative;
tokens do not grant a stale role. The default lifetime is 900 seconds. Production
validation permits 5–30 minutes.

V34 initializes every existing user's `security_version` to `1`. Tokens created
before deployment have no `sv` claim and are rejected after the new backend is
deployed. Incrementing the version revokes all existing tokens for that user.
This occurs on authenticated password change, successful recovery/reset,
successful email or identity change, administrative email change, account
deactivation, self revocation, and administrator revocation.

## OTP and recovery policy

Password/setup OTPs and email-change OTPs remain six digits. They use
`SecureRandom`, are stored as adaptive password hashes, expire after 10 minutes,
allow at most five failed attempts, and become unusable after success, expiry, or
lockout. Six digits are retained for email usability because the database also
enforces short expiry, single-use row locking, per-challenge attempts, and
account/IP throttling.

Default issuance controls are:

- 60-second account resend cooldown
- 5 account issuances per hour
- 10 account issuances per day
- 20 IP issuances per hour

Default verification controls are 20 account and 60 IP attempts per 15 minutes.
All values are configuration properties. Rate-limit events are stored in
PostgreSQL and counted inside a true rolling time window, so limits are shared
across Render instances. A transaction-scoped PostgreSQL advisory lock serializes
updates for each hashed action/dimension/identifier key; expired events are
deleted and denied attempts do not create unbounded rows. Identifier values are
hashed in rate keys and audits.

Password reset and email verification bearer tokens contain 256 random bits and
only their SHA-256 digests are stored. The raw token is returned or mailed once,
never logged, and is compared by hashing the presented value. Reset tokens expire
after 10 minutes. Email verification tokens expire after 24 hours. Pessimistic
row locks provide single-use behavior under concurrent requests.

## Stored authentication fields

The `users` table stores:

- `security_version`
- `must_change_password`, `password_changed_at`, and `last_login_at`
- `password_otp_hash`, `password_otp_expires_at`,
  `password_otp_failed_attempts`, `password_otp_issued_at`, and
  `password_otp_purpose`
- `password_reset_token_hash` and `password_reset_expires_at`
- `email_verification_token_hash` and `email_verification_expires_at`
- `pending_email`, `email_change_otp_hash`,
  `email_change_otp_expires_at`, `email_change_otp_failed_attempts`, and
  `email_change_otp_issued_at`

`auth_rate_limit_buckets` contains shared, atomic throttle state. V42 retains
`auth_rate_limit_events` temporarily so an application rollback remains possible.
`security_audit_events` contains actor/target foreign keys, event, outcome, safe
reason/details, hashed identifier, client metadata, correlation ID, and time.
It never stores credentials, raw OTPs/tokens, JWTs, or signing/mail secrets.

The previous process-local `LoginRateLimiter` remains for login only. OTP paths
do not depend on it. Moving login throttling to shared storage is a remaining
risk.

## Email transaction boundary

OTP/token state is created and committed before the external mail webhook is
called, so no database transaction is held across network I/O. Account setup,
email change, email verification resend, and registration return an explicit
failure state when delivery fails. Password recovery must remain
account-enumeration resistant: it always returns “if eligible” wording, records
provider failure internally, and does not assert that delivery succeeded.
An outbox with retry remains deferred.

## Migration and rollout

V34 is forward-only. It:

- adds the version and OTP attempt/issued/purpose fields and check constraints;
- renames legacy credential columns to explicit hash names;
- creates the rate-limit and structured-audit tables and indexes; and
- invalidates all outstanding legacy OTPs, reset links, verification links, and
  email-change OTPs because the database cannot safely distinguish raw legacy
  bearer values from the new representation.

V34 does not deactivate, delete, or re-enable users. V33-contained accounts stay
disabled. There is no safe down migration: restoring the old application after
V34 would require a database restore or a separately reviewed forward
compatibility migration. Do not rename columns back while the new backend is
running.

Deployment order:

1. Back up PostgreSQL and confirm V33 is current and contained accounts remain
   disabled.
2. Configure a strong `JWT_SECRET`, stable `JWT_ISSUER`, `JWT_AUDIENCE`, and
   `JWT_KEY_ID`, and a 5–30 minute `JWT_EXPIRATION_MS`.
3. Configure the OTP variables listed in `.env.example`.
4. Deploy the backend first and allow Flyway V34 plus Hibernate validation to
   finish.
5. Verify health, login, a protected endpoint, password recovery delivery, a
   rejected pre-V34 token, and both 401/403 response shapes.
6. Deploy the frontend so revoked/expired-token redirects and logout-all UI are
   active.
7. Inform users that they must sign in again and request new outstanding OTP or
   verification/reset links.
8. Monitor `security_audit_events`, 429 rates, email-provider failures, and
   authentication 401 rates.

Changing `JWT_SECRET` is not required merely to deploy V34 because missing
security-version claims already force login. Rotate it if incident response or
key hygiene requires it; doing so also invalidates every access token.

## Configuration

New or changed variables:

- `JWT_EXPIRATION_MS`
- `JWT_ISSUER`
- `JWT_AUDIENCE`
- `JWT_KEY_ID`
- `OTP_VALIDITY_SECONDS`
- `RESET_TOKEN_VALIDITY_SECONDS`
- `OTP_RESEND_COOLDOWN_SECONDS`
- `OTP_MAX_VERIFICATION_ATTEMPTS`
- `OTP_ACCOUNT_ISSUANCE_LIMIT_PER_MINUTE`
- `OTP_ACCOUNT_ISSUANCE_LIMIT_PER_HOUR`
- `OTP_IP_ISSUANCE_LIMIT_PER_HOUR`
- `OTP_ACCOUNT_ISSUANCE_LIMIT_PER_DAY`
- `OTP_ACCOUNT_VERIFICATION_LIMIT_PER_15_MINUTES`
- `OTP_IP_VERIFICATION_LIMIT_PER_15_MINUTES`

## Deferred risks

- The access token remains readable by browser JavaScript in `sessionStorage`;
  there is no refresh-token rotation/reuse detection or HttpOnly cookie yet.
- Ordinary logout is browser-local until the short access token expires.
- Login throttling is still process-local.
- The email provider has no transactional outbox or automatic retry.
- Client IP correctness assumes the deployment proxy owns the final
  `X-Forwarded-For` hop.
- Manager scope, upload hardening, business constraints, broader frontend
  refactoring, and deployment hardening remain outside Phase 1.
