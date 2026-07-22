# Phase 6 — Transactional outbox and notification reliability

## Scope

This phase changes only transactional delivery, in-app event identity, email-provider reliability,
retry/retention operations, and truthful delivery wording. Manager scope, domain transitions,
uploads, JWT/session behavior, and existing V1–V38 migrations are unchanged.

## Delivery inventory

| Flow | Domain transaction and audit | Final in-app policy | Final email policy | Recipient / sensitivity |
|---|---|---|---|---|
| Admin creates staff account | User, settings, staff audit, and outbox commit together | None | Required security delivery, `ACCOUNT_SETUP`, queued; API says `QUEUED` | New address; encrypted temporary credential; expires after 24h |
| First-time setup OTP | Locked user OTP state, security audit, and outbox commit together | None | Required security delivery, `ACCOUNT_SETUP_OTP`; retry until OTP expiry | Account address; encrypted raw OTP cleared at terminal state |
| Email verification / resend | Locked verification-token state and outbox commit together | None | Required security delivery, `EMAIL_VERIFICATION`; ordinary preferences ignored | Current address; encrypted bearer token; eligibility rechecked |
| Password recovery request | Rate-limit/security audit plus eligible OTP state and outbox commit together | None | Required security delivery, `PASSWORD_RECOVERY_OTP`; response remains account-generic | Eligible account only; encrypted OTP; enumeration-sensitive |
| Password reset completion | Password/security version/security audit commit together | None | No completion email existed; remains `NOT_REQUIRED` | Authenticated by short-lived reset token |
| Email change request | Pending address/OTP/security audit/outbox commit together | None | Required security delivery, `EMAIL_CHANGE_OTP`; preferences ignored | New encrypted address and OTP; pending-address eligibility check |
| Leave creation | Leave row and domain checks commit together | None in current product | No email in current product | Employee action; audit is not currently emitted on creation |
| Leave approval/rejection | Locked leave/balance mutation, security audit, notification, and optional outbox commit together | Stable `LEAVE_REVIEWED` event | Optional operational email controlled by leave preference | Employee; cancelled/changed status becomes ineligible |
| Leave cancellation | Locked leave transition commits | No new notification in current product | No email; any queued obsolete review is cancelled by eligibility | Employee action |
| Attendance correction submission | Correction and security audit commit together | None in current product | No email | Employee action |
| Attendance correction approval/rejection | Correction/session mutation, security audit, notification, and optional outbox commit together | Stable review event | Optional via attendance preference | Employee |
| Attendance override | Session mutation and security audit commit together | None in current product | No email | Administrative operation |
| Task assignment/reassignment | Task mutation, notification, and optional outbox commit together | Stable assignment event per task version | Optional via task-assigned preference | Current assignee; assignment eligibility rechecked |
| Task completion/status change | Task mutation, audit, completion notification/outbox commit together when applicable | Stable completion event | Optional completion email; other status changes remain audit-only | Task creator for completion |
| Task comment/mention | Comment, notification, and optional outbox commit together | Stable event from comment ID and recipient | Optional via comment preference | Mentioned users and assignee |
| Due/overdue task reminder | One notification/outbox transaction per task/day | Stable daily event | Optional reminder email | Current assignee |
| Project assignment | Project assignment, notification, and optional outbox commit together | Stable assignment event per project version | Optional via project-assigned preference | Assigned employee; membership eligibility rechecked |
| Project status change | Project mutation and existing audit commit | No notification in current product | No email | Existing behavior retained |
| Payment creation | Payment/idempotency/domain audit transaction | No notification in current product | No email | Existing behavior retained |
| Manager-scope assignment | Scope mutation and security audit transaction | No notification in current product | No email | Existing behavior retained |
| HR-document availability | Document/media attachment and staff/security audit transaction | No notification in current product | No email | Existing behavior retained |
| Administrative account changes | User mutation, staff audit, and session/security events commit | No notification in current product | No email | Existing behavior retained |
| Provider calls/webhooks | Google Apps Script is the only email delivery request | N/A | Worker-only after claim commit; response contract is validated | Shared secret never logged; outbox ID used as delivery key |
| Cloudinary operations | Existing upload/deletion flow | N/A | Outside Phase 6 | Provider/database deletion reconciliation remains deferred |

Email is mandatory for security workflows in the sense that successful domain issuance requires a
durable outbox row. The API does not wait for provider acceptance. Operational email is advisory;
its failure never rolls back the underlying business action. In-app notification creation remains
required and transactional. Suppression decisions are persisted as `SUPPRESSED` outbox rows.

## Database model (V39)

`outbox_messages` stores a UUID message ID, stable domain event ID/type, email channel, optional user
reference, SHA-256 recipient diagnostic hash, AES-GCM encrypted address and minimal payload,
template, priority, status, attempt limits, availability/claim timestamps, expiry, safe error fields,
provider ID, correlation ID, and optimistic version. Controlled checks allow `PENDING`, `PROCESSING`,
`SENT`, `RETRY`, `FAILED`, `CANCELLED`, `EXPIRED`, and `SUPPRESSED`.

`outbox_delivery_attempts` is append-only per message/attempt and records safe outcomes and timing.
The database unique constraints on `(event_id,event_type,recipient_address_hash,channel,template_key)`
and `idempotency_key` enforce exactly-once logical creation. External delivery is not claimed to be
exactly once.

Notifications gain nullable `event_id`/`event_type` columns and a partial unique index on event and
recipient. Null identifies readable legacy rows; no fabricated event identity or delivery status is
backfilled. V39 also adds the attendance email preference.

Indexes cover ready ordering, stale processing, failed operations, event lookup, retention, and
attempt history. V39 is forward-only. Rolling application code back after migrating is unsafe because
old code would not populate event identity/outbox rows; database rollback requires restoring the
pre-migration backup and draining or intentionally discarding post-migration messages.

## Transaction and worker model

The domain service mutates domain state, writes required audit/in-app rows, and inserts the outbox row
through the same Spring transaction. An outbox insert error therefore rolls the caller back. No
provider method is callable from domain packages; `EmailService.deliver` is used only by the worker.

Each instance has a random worker ID and a bounded fixed executor. Polling claims a bounded batch in a
short transaction with `FOR UPDATE SKIP LOCKED`, changes rows to `PROCESSING`, commits, and only then
does network I/O. Claims older than the processing timeout are reclaimable. Shutdown first stops
claiming, then waits up to 30 seconds for active delivery before interrupting it. Provider timeout must
remain shorter than the processing timeout.

Before sending, security token expiry/state, user activation, leave status, task assignee, and project
membership are rechecked. Obsolete rows become `EXPIRED`; immutable historical completion/comment
events only require an active recipient.

## Retry and provider contract

Timeout, network/DNS I/O, provider 429/5xx, and explicit temporary rejection retry. Invalid templates,
validated 4xx/permanent rejection, invalid payload, and disabled/unsupported delivery are permanent.
Backoff is 1 minute, 5 minutes, 15 minutes, 1 hour, then capped exponential delays with 0–20% jitter.
Maximum attempts default to six.

The Google Apps Script must be redeployed from `docs/google-apps-script-mail.js`. It validates the
shared secret and required contract fields. Script locking plus a six-hour cache of the outbox message
ID suppresses normal timeout retries. Apps Script does not expose Gmail's actual message ID, so it
returns the outbox delivery ID as its acceptance identifier. Cache expiry, provider behavior, or a
timeout outside that window still leaves an unavoidable at-least-once duplicate risk. Email links are
idempotent bearer-token verification actions and duplicate receipt does not itself mutate state.

## Security and retention

`OUTBOX_ENCRYPTION_KEY` derives an AES-256-GCM key; production startup refuses missing, short, or
placeholder keys. Addresses are not logged or returned by admin APIs. Only a hash is exposed for
support correlation. Raw passwords, hashes, JWTs, reset-session tokens, Cloudinary secrets, private
URLs, HR details, and unrelated profile data are not stored. The unavoidable setup password, OTP, and
verification token fragments are encrypted, expiry-bound, eligibility-checked, and cleared on sent,
expired, cancelled, permanent failure, or exhausted retry.

Sent rows are deleted in locked batches after 30 days. Cancelled/expired/suppressed rows are deleted
after 90 days. Unresolved `FAILED` rows are deliberately preserved for operator review; attempts are
deleted only with their parent. Security/domain audit retention is unchanged. In-app notification
retention is unchanged. Expected growth is one outbox row per intended recipient/channel plus one row
per attempt; normal sent traffic retains roughly 30 days.

## Administrative API and observability

All endpoints require backend `ADMIN` authority:

* `GET /api/v1/admin/outbox/failed?page=0&size=20`
* `GET /api/v1/admin/outbox/{id}/attempts`
* `GET /api/v1/admin/outbox/stats`
* `POST /api/v1/admin/outbox/{id}/retry`
* `POST /api/v1/admin/outbox/{id}/cancel`

Responses exclude ciphertext and plaintext addresses/payloads. Manual retry is allowed only for a
non-expired failed message whose payload remains and attempts are available; recipient and payload
cannot be edited. Retry/cancel writes a security audit event. Queue stats and structured logs expose
pending/retry/processing/failed depth, oldest age, recent outcomes, acceptance latency, stale recovery,
duplicate prevention, cleanup, event IDs, message IDs, and provider acceptance IDs without PII.
Provider outage does not make the whole application unready; database/outbox failure still fails the
required transaction.

## Environment variables

* `OUTBOX_WORKER_ENABLED` (default `false`)
* `OUTBOX_POLL_INTERVAL` (`10s`)
* `OUTBOX_BATCH_SIZE` (`20`, bounded 1–100)
* `OUTBOX_WORKER_CONCURRENCY` (`2`, bounded 1–8)
* `OUTBOX_PROCESSING_TIMEOUT` (`5m`)
* `OUTBOX_MAX_ATTEMPTS` (`6`, bounded 1–20)
* `OUTBOX_INITIAL_RETRY` (`1m`)
* `OUTBOX_SENT_RETENTION` (`30d`)
* `OUTBOX_TERMINAL_RETENTION` (`90d`)
* `OUTBOX_SENSITIVE_RETENTION` (`24h`; an upper bound, while shorter event/OTP expiry remains authoritative)
* `OUTBOX_PROVIDER_CONNECT_TIMEOUT` (`10s`)
* `OUTBOX_PROVIDER_READ_TIMEOUT` (`20s`)
* `OUTBOX_ENCRYPTION_KEY` (required strong secret in production)
* `OUTBOX_CLEANUP_CRON` (`0 37 3 * * *`)
* `OUTBOX_METRICS_INTERVAL` (`60s`)

## Deployment and rollback procedure

1. Take a custom-format production backup with a PostgreSQL 17 client and verify it with `pg_restore --list`.
2. Restore that backup into an isolated PostgreSQL 17 database and rehearse V39 plus Flyway validation.
3. Deploy the revised Apps Script to staging; test success, malformed response, auth failure, temporary failure, rate limit, and duplicate delivery key.
4. Generate and configure `OUTBOX_ENCRYPTION_KEY`; configure all worker/timeouts with the worker disabled.
5. Deploy backend with `OUTBOX_WORKER_ENABLED=false`; confirm V39, API health, and admin authorization.
6. Trigger one authenticated advisory notification and confirm exactly one `PENDING` or `SUPPRESSED` row and the truthful `QUEUED` response where applicable.
7. Enable one worker instance and verify claim, attempt, provider acceptance, payload clearing, and in-app behavior.
8. Force a staging temporary provider failure; verify `RETRY`, backoff, history, and eventual `SENT`.
9. Verify an expired OTP becomes `EXPIRED` without provider delivery.
10. Scale backend instances gradually; confirm unique claims and stable queue depth.
11. Monitor failed depth, oldest pending age, latency, acceptance/failure counts, and stale recovery.
12. Deploy the frontend wording/preference update and smoke-test setup, recovery, email change, and resend flows.
13. As an admin, test failed listing, safe metadata, eligible retry, cancellation, and security audit entries.
14. For application rollback, disable workers first and allow active calls to finish. Do not roll back to code that directly sends mail while V39-era domain actions continue. Either fix forward, or restore the verified pre-V39 database backup and reconcile all messages/actions created after that backup. Queue draining must keep the same encryption key.

## Verified tests and remaining risks

The full suite is designed to run against PostgreSQL 17 through `leaflet.test.database.*`. It covers
clean V1–V39 migration, logical uniqueness, two simultaneous `SKIP LOCKED` claims, transactional
rollback, legacy notification readability, provider contract outcomes, encrypted payloads, retry
backoff, worker outcome handling/shutdown, and admin authorization, along with all Phase 0–5 tests.

Deferred risks remain: Cloudinary deletion reconciliation, broad query pagination/performance, large
service refactoring, HttpOnly refresh-token architecture, formal metrics backend/alerts, backup and
container hardening, key rotation with multiple decrypt keys, and provider idempotency beyond the
Apps Script six-hour cache. Phase 6 does not by itself establish complete production safety.
