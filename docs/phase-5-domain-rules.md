# Phase 5 — Leave, Attendance, Project, Payment, and Task Rules

Date: 2026-07-20  
Business zone: `ATTENDANCE_ZONE_ID` (default `Asia/Kathmandu`)

## Phase 4 prerequisite

The skipped Phase 4 PostgreSQL suite was executed against PostgreSQL 15.18 before Phase 5 work. All migrations V1–V37 validated and the Phase 4 migration, authorization, security, locking, and concurrency tests passed. One incorrect optimistic-lock test expectation was corrected: project creation already leaves the aggregate at version 1, so the assertion now uses the captured initial version rather than assuming zero.

## Implemented policy

### Time

- Domain decisions use an injected `Clock` through `BusinessClock`.
- The business date is calculated in the configured business zone.
- JPA lifecycle callbacks still use UTC `Instant.now()` only for persistence timestamps; they do not decide eligibility, deadlines, balances, or transitions.

### Leave

- Only annual and sick leave remain enabled; the dormant personal/unpaid enum values were not enabled without product approval.
- Date ranges are inclusive.
- Past start dates are rejected; same-day requests are allowed.
- Active `PENDING` and `APPROVED` requests may not overlap for one user.
- Creation serializes on the employee row, and V38 adds a PostgreSQL exclusion constraint as the concurrency backstop.
- Review and cancellation use an explicit transition policy. Approved, rejected, and cancelled states are terminal.
- Approval/rejection requires a review reason.
- Approval rejects deactivated employees and rechecks the authoritative balance while holding the leave request lock.
- Balance usage counts only approved leave and slices cross-period requests at the configured reset-month boundary.
- Carry-forward and accrual are disabled because no approved policy or historical ledger exists.
- Existing annual and sick adjustment fields continue to apply to each current reset period for compatibility.
- Holidays are stored in `company_holidays`; active holidays do not consume leave.
- Weekend exclusion is configurable with `LEAVE_EXCLUDE_WEEKENDS` and defaults to `false`, preserving the prior calendar-day behavior.
- Request, cancellation, and review decisions are audited. Review decisions also create a transactional in-app notification; no external email is sent inside the transaction.

### Attendance

- Normal sessions, active sessions, breaks, corrections, and daily summaries share one calculation service.
- Totals use whole elapsed minutes, subtract recorded breaks, clamp at zero, and derive hours only for persistence display.
- Daily summaries expose required, grace, remaining, overtime, and shortfall minutes.
- `WORKED_ON_LEAVE` and `OVERTIME` are explicit statuses.
- Approved leave blocks employee self-start. An authorized manager/admin may override with a required reason, which is audited.
- Self start/end and break lifecycle changes are audited.
- Authorized override start/end requires a reason and rejects deactivated users.
- Normal session close enforces `ATTENDANCE_MAX_SESSION_MINUTES` (default 1440). An authorized override is required to close an over-limit session.
- Corrections reject active sessions, future end times beyond five minutes, overlaps, durations over the configured maximum, and submissions later than `ATTENDANCE_CORRECTION_DEADLINE_DAYS` (default 30).
- Correction approval uses the same duration/hour calculator and remains atomic with its audit event and in-app notification.

### Projects

- Allowed transitions are:
  - `PLANNED -> ACTIVE | ON_HOLD`
  - `ACTIVE -> ON_HOLD | COMPLETED`
  - `ON_HOLD -> ACTIVE | COMPLETED`
  - `COMPLETED` is terminal
- Completed projects are read-only for project edits, task creation/changes/comments/deletion, and board creation/reordering.
- Status changes and completion are audited.
- Project date order, nonnegative budget, controlled status, and optimistic version remain database-enforced by V37.

### Payments

- Amount must be positive.
- A payment cannot be more than five minutes in the future or before the project start date.
- Payments remain allowed after project completion so finance can reconcile late receipts; changing this requires an approved archival/accounting policy.
- `idempotencyKey` is optional for backward compatibility. The frontend supplies a UUID, retains it across failed retries, and rotates it after success.
- V38 enforces one idempotency key per project. A completed retry returns the existing payment; a simultaneous duplicate has one database winner.
- Attachment ownership and transactional audit behavior remain from Phases 3 and 4.

### Tasks

- Core transitions are:
  - `TODO -> IN_PROGRESS | BLOCKED | DONE`
  - `IN_PROGRESS -> BLOCKED | DONE`
  - `BLOCKED -> IN_PROGRESS | DONE`
  - `DONE -> IN_PROGRESS` only for a project task manager
- Any active custom-board task can move directly to `DONE`; other custom-board moves are limited to an adjacent configured board. Reopening a terminal custom board requires project task-management authority.
- Completed-project tasks are read-only.
- Task status changes, completion, and reopening are audited.
- Transactional task notifications now persist in-app only, avoiding external email I/O in the transaction.
- Due-date validation and scheduled reminders use the injected business clock.

## V38 migration

`V38__domain_policy_integrity.sql`:

- fails fast if existing pending/approved leave requests overlap;
- creates `company_holidays`;
- adds the active-leave exclusion constraint;
- adds payment idempotency storage and its partial unique index.

It does not rewrite or invent historical leave, attendance, project, task, or payment data.

## Verification

- PostgreSQL 15.18 clean migration V1–V38 and validation: passed.
- PostgreSQL exclusion/idempotency concurrency tests: passed with exactly one winner.
- Phase 4/5 focused suite: 40 tests, 0 failures, 0 errors.
- Fixed-clock, cross-period leave, attendance arithmetic, and transition unit tests: passed.
- Next.js production build, lint, and TypeScript validation: passed.

## Policy decisions still requiring product confirmation

1. Whether weekends should consume leave. Compatibility default is calendar days (`LEAVE_EXCLUDE_WEEKENDS=false`).
2. Holiday administration UI and the authoritative source/calendar for inserting `company_holidays`.
3. Whether legacy balance adjustments are permanent per-period adjustments or should become dated ledger entries. A ledger migration was not invented.
4. Accrual, carry-forward caps/expiry, and unpaid/personal leave entitlements.
5. A minimum-minute threshold for `ABSENT`. Existing behavior remains `NO_ACTIVITY` or `UNDER_HOURS`; no threshold was invented.
6. Whether attendance on leave should also affect payroll or leave consumption. It is surfaced and audited but does not silently change either record.
7. Closed cross-midnight sessions store only aggregate break minutes, not break intervals. Exact allocation of a historical break across two dates cannot be reconstructed without a break-event schema.
8. Whether payments should be forbidden after project completion. Current behavior permits financial reconciliation.
9. Completion prerequisites such as all tasks done, due-date presence, or budget/payment reconciliation. None were invented.
10. A task status history table. Security audit events preserve transitions, but they are not a product-facing task history ledger.
