# Phase 4 — Database Integrity, Locking and Concurrency

## Scope confirmation

This change is limited to Phase 4 database integrity, transactional concurrency,
safe conflict handling, migration validation, and the minimal client refreshes
needed after conflicts. It does not redesign authentication, manager scope,
uploads, leave policy, attendance UI, or external-delivery architecture. V1–V36
remain unchanged.

## Integrity inventory

| Area | Existing protection | Missing risk found | Phase 4 enforcement |
|---|---|---|---|
| Attendance start | Service pre-check | Two employee/override requests could both insert | User-row serialization, partial unique index, exclusion constraint, `409` mapping |
| Attendance mutation | Transactional service | End/break/heartbeat loaded without a write lock | Active-session `PESSIMISTIC_WRITE` query and `@Version` |
| Correction submission | Service pre-check | Concurrent pending corrections could both insert | User/session locks and partial unique index |
| Correction review | Correction row lock from Phase 2 | Target session was not explicitly locked; overlap and break totals were unsafe | Correction, owner and target-session locks; overlap lock query; exclusion constraint; net-duration calculation |
| Leave review | Phase 2 row lock and scope policy | Retry returned `400`; cancellation and balance adjustment were unlocked | `409` terminal conflict, locked cancellation, locked user balance mutation, `@Version` |
| Projects/tasks/payments/boards | Transactions and foreign keys | Lost-update detection absent | Deliberate `@Version` columns and nonnegative version checks |
| Email/pending email | Application lowercasing and exact-case indexes | Direct SQL and current-vs-pending claims could bypass intended identity uniqueness | Normalizing trigger, functional indexes, and cross-field identity-claim registry |
| Employee ID | Case-insensitive repository lookup | Database uniqueness was case-sensitive | Case-insensitive trimmed functional unique index |
| Board name | Case-insensitive service pre-check | Database uniqueness was case-sensitive | Per-project normalized unique index |
| Projects/payments | DTO/service checks in some paths | Direct SQL allowed negative budget, invalid dates, or nonpositive payment | Database checks |
| Controlled values | Java enums | Direct SQL could create values Hibernate cannot read | CHECK constraints matching current Java values; task status stays extensible/nonblank |
| Daily logs | Unique `(user_id, log_date)` and service validation | No Phase 4 race requiring a new rule | Intentionally unchanged |
| Notifications | Recipient/read and recipient/created indexes | Review notification side effects needed transaction coupling | Correction notification is database-only in the review transaction |
| Media/scope/auth tokens | Phase 1–3 row locks, versions, checks, lifecycle rules | No Phase 4 gap requiring redesign | Existing protections retained; controlled media values added |

All principal relationships inspected already have foreign keys. No new
relationship was inferred. Dirty data is never silently deleted, merged,
approved, rejected, financially rewritten, or timestamp-shifted.

## Files created

- `db/migration/V37__database_integrity_locking_and_concurrency.sql` — fail-fast
  validation, version columns, constraints, identity registry, normalized
  uniqueness, exclusion constraint, and access-path indexes.
- `docs/phase-4-dirty-data-runbook.sql` — read-only predeployment diagnostics and
  manual-remediation rules.
- `src/test/java/com/ems/backend/migration/DatabaseIntegrityMigrationTest.java`
  — PostgreSQL migration, direct-SQL constraint, dirty-fixture, and concurrent
  insertion tests.
- This report.

## Files modified

- Attendance entities, repositories and services — versions, lock queries,
  atomic start/review operations, overlap and break-safe correction handling.
- Leave entity, repository and service — versioned and locked review,
  cancellation and balance updates with conflict semantics.
- Project, payment, task and board entities — optimistic versions; board
  metadata now reflects normalized database uniqueness.
- User repository and identity write services — normalized writes and
  case-insensitive reads/checks.
- `GlobalExceptionHandler` — stable safe constraint, optimistic-lock and
  retryable lock responses.
- `NotificationService` — database-only notification path for a caller-owned
  approval transaction.
- `application.yml` — Hibernate JDBC timezone fixed to UTC.
- PostgreSQL integration/migration tests — Phase 4 races and latest Flyway
  version expectations.
- Attendance and leave pages — refresh authoritative state after a failed
  concurrent mutation.

## Database migration

V37 adds `version BIGINT NOT NULL DEFAULT 0` plus `version >= 0` to attendance
sessions, attendance corrections, leave requests, projects, project payments,
tasks and project task boards. Media assets and manager scope were already
versioned. Users remain intentionally unversioned: security-sensitive user
mutations use Phase 1 pessimistic locks and `security_version`. Daily logs are
protected by their per-day unique key. Notifications are recipient-owned
append/read records. Notes, comments and milestones remain intentionally
unversioned because Phase 4 found no shared edit transition on them.

New integrity rules include:

- one open attendance session per user;
- one pending correction per attendance session;
- no overlapping half-open attendance ranges, including open-ended ranges;
- positive session ordering, nonnegative break/total, and break not exceeding
  elapsed duration;
- correction duration no greater than the existing 24-hour service rule;
- consistent reviewer fields for terminal leave/correction states;
- nonnegative project budget and ordered project dates;
- positive payment amount;
- nonblank project, task and board identifiers;
- current Java role, employment, project, priority, leave, correction,
  notification, document and media controlled values.

Email and pending email use `lower(btrim(...))` uniqueness. A
`user_identity_claims` table and users trigger prevent the same normalized value
from being a current email and any pending email concurrently. Existing values
are checked but not rewritten. New identity writes are trimmed/lowercased.
Employee IDs and project-board names use normalized functional indexes because
their existing repository behavior proves case-insensitive semantics. Superseded
exact-case indexes are removed in the same transaction.

The attendance exclusion constraint uses `btree_gist` and
`tsrange(start_time, coalesce(end_time, 'infinity'), '[)')`. Adjacent sessions
remain valid; overlapping and open-ended conflicts do not.

### New index rationale

| Index | Query supported / ordering | Cost |
|---|---|---|
| `idx_projects_manager_id` | Manager-owned projects | One entry per project |
| `idx_project_assignments_user_project` | Reverse membership lookup; user first | One entry per assignment |
| `idx_attendance_sessions_user_time_range` | User history and range scans; newest start first | One entry per session; replaces user-only index |
| pending-correction queue | Oldest pending reviews only | Partial; pending rows only |
| `idx_tasks_status_due` | Status/due reminder work | Partial; dated tasks only |
| leave user/status/date range | User/status overlap reads | One entry per leave row |
| pending-leave queue | Oldest pending reviews only | Partial; pending rows only |
| audit actor/created and created | Actor history and chronological incident lookup | Two audit write entries |

Daily-log, notification, manager-scope, media-cleanup and existing security
indexes were already suitable and were not duplicated. `EXPLAIN` evidence could
not be gathered without an isolated production-like dataset, so no measured
performance improvement is claimed.

V37 is transactional and fail-fast. It reports representative dirty identities
or row categories and points to the runbook. It never repairs business data.
Rollback after production use requires restoring the verified pre-V37 backup;
down-migrating constraints and versioned writes is unsupported.

## Timestamp decision

The schema is historically mixed. Most legacy instant columns are PostgreSQL
`TIMESTAMP` while project payment/note fields and newer heartbeat/settings fields
use `TIMESTAMPTZ`. Java consistently uses `Instant` for instants and `LocalDate`
for business dates; no `LocalDateTime`, `OffsetDateTime`, or `ZonedDateTime`
persistence model was found. Historical values were not converted because their
original interpretation cannot be proven from code alone. Hibernate JDBC now
uses UTC. New real-world instant columns should use `TIMESTAMPTZ`; business-date
policy continues to use the configured attendance/business timezone.

## Concurrency model

- Attendance start locks the target user, checks current state, inserts and
  flushes. Both self-start and manager override use this path. The partial unique
  index and exclusion constraint remain authoritative backstops.
- Active attendance end/break/heartbeat obtains a pessimistic session lock.
- Correction creation locks the owner and target session; the partial unique
  index resolves any remaining database-level race.
- Leave approve/reject and cancel lock the leave row. Only a pending row can
  transition; the loser receives `409`.
- Correction approve/reject locks the correction. Approval additionally locks
  the owner and target session and range-locks matching sessions before update.
- Successful correction review flushes session and decision, writes mandatory
  audit metadata, and creates one in-app notification in the same transaction.
  Any database failure rolls the transaction back. No email/provider call is
  made while holding these locks.
- `@Version` detects lost updates on shared mutable workflow records. A stale
  update maps to `CONCURRENT_UPDATE_CONFLICT` (`409`). Pessimistic lock
  acquisition failure maps to retryable `DATABASE_RETRY_REQUIRED` (`503`).
- No explicit SQL conditional-update statement was added because the existing
  Phase 2 pessimistic review locks plus pending-state check and `@Version`
  satisfy the allowed atomic-transition model.

## Attendance correction integrity

Approval revalidates requested ordering, the existing 24-hour limit and
future-end rule. It excludes the target session and checks half-open overlap
against locked sessions; the exclusion constraint is the final direct-SQL and
phantom-race guard. Saved break minutes are preserved. An active break is closed
at the corrected end, and the operation rejects an end before that break or a
break longer than elapsed time. `total_hours` is recalculated from net minutes.

The audit `details` value is structured JSON text containing correction/session
IDs, original/result instants, break minutes and net minutes. Rejection records
the correction/session identity without changing the session. Maximum work
policy, cross-midnight policy and the complete attendance-policy redesign remain
deferred.

## API changes

- Duplicate active session: `409 ATTENDANCE_SESSION_ALREADY_ACTIVE`.
- Duplicate pending correction: `409 ATTENDANCE_CORRECTION_ALREADY_PENDING`.
- Attendance overlap: `409 ATTENDANCE_SESSION_OVERLAP`.
- Reviewed/cancelled terminal retry: `409 RESOURCE_CONFLICT`.
- Normalized email conflict: `409 IDENTITY_ALREADY_EXISTS`.
- Employee ID conflict: `409 EMPLOYEE_ID_ALREADY_EXISTS`.
- Board identity conflict: `409 PROJECT_BOARD_NAME_CONFLICT`.
- Optimistic conflict: `409 CONCURRENT_UPDATE_CONFLICT`.
- Retryable database lock failure: `503 DATABASE_RETRY_REQUIRED`.
- Database connectivity failure: `503 DATABASE_UNAVAILABLE`.
- Other CHECK violations: safe `400 DATABASE_CONSTRAINT_VIOLATION`.

Responses retain the request correlation ID. SQL, table names, connection data
and Hibernate details are not returned. Versions are internal and are not added
to response DTOs, so there is no client payload contract change. Attendance and
leave screens refresh after failed mutations.

## Tests

Local result on 2026-07-20:

- Discovered: 114
- Passed: 81
- Failed: 0
- Errors: 0
- Skipped: 33
- Backend compile/test compilation: passed
- Frontend Next.js production build/type check: passed
- Flyway/PostgreSQL/Testcontainers tests: skipped because Docker was unavailable
  and no isolated PostgreSQL URL was supplied
- Local PostgreSQL: no server responding on `localhost:5432`
- PostgreSQL production major version: not verifiable from the workspace

The skipped PostgreSQL suites contain clean/staged migration, dirty-data,
direct-SQL constraint, concurrent attendance/correction insertion,
employee-vs-manager start, correction approve-vs-reject, duplicate correction,
leave review races, single audit/notification, rollback, project/task/payment
optimistic races and current-version retry, Phase 0 containment, Phase 2 scope
and Phase 3 media assertions. They must run against PostgreSQL before
deployment. H2 results are not presented as a substitute.

The current test gap is explicit: the PostgreSQL suites have not executed, and
timezone-boundary integration scenarios still need production-compatible
coverage before Phase 4 can be called fully verified.

## Breaking changes

- Duplicate active attendance, pending correction, normalized identity,
  employee ID and board-name data is now rejected.
- Existing overlaps, duplicates or invalid financial/status/reviewer data block
  V37 and require reviewed manual remediation.
- Email/pending-email uniqueness is trimmed and case-insensitive across both
  fields.
- Concurrent stale mutable-workflow updates now fail rather than overwrite.
- A repeated terminal approval/rejection receives `409`.
- Direct invalid database values now produce constraint-driven client errors.

## Environment variables

No environment variable was added or renamed. Existing database and attendance
timezone variables remain unchanged. Hibernate JDBC instant handling is fixed to
UTC in configuration.

## Manual deployment procedure

1. Take a production backup and prove restore into a disposable environment.
2. Run `phase-4-dirty-data-runbook.sql` read-only against the restored copy and
   then production.
3. Have HR/operations/finance owners manually resolve every result with an audit
   trail; never auto-select attendance, identity, money, or approval truth.
4. Rehearse V1→V37 and V1→V36→V37 on an isolated PostgreSQL instance matching
   production major version; run all PostgreSQL and concurrency tests.
5. Deploy the backend with V37 and keep frontend deployment paused.
6. Verify Flyway reports V37, Hibernate validation succeeds, and every new
   constraint/index is present.
7. Race employee start against manager override for a nonproduction employee;
   verify one `200`, one `409`, and one active row.
8. Race correction and leave terminal decisions; verify one transition, one
   audit and, for correction, one notification.
9. Try case/whitespace variants of a test email and pending email; verify safe
   conflicts and unchanged legitimate identity.
10. Deploy the frontend and confirm conflict messages refresh current state.
11. Monitor correlation IDs, constraint identifiers, retryable-lock responses,
    audit counts and connection saturation.
12. For a severe migration incident, stop writes and restore the verified
    pre-V37 backup plus predeployment application. Do not edit Flyway history or
    attempt an ad-hoc down migration.

## Remaining risks

- Complete leave entitlement/overlap policy and complete attendance policy.
- Email outbox and durable provider delivery.
- Provider/database reconciliation for existing upload cleanup states.
- Representative-data `EXPLAIN (ANALYZE, BUFFERS)` performance work.
- Large-service refactoring and broad pagination.
- HttpOnly refresh-token/session architecture.
- Deployment, backup, monitoring and infrastructure hardening.
- PostgreSQL execution of the skipped suites and timezone-boundary tests.

Passing application tests alone does not establish production safety.
