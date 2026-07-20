# Phase 2 manager scope and authorization

## Scope and policy decision

This phase changes only manager scope, employee ownership, reviewer separation,
directory privacy, project financial authorization, and the audit/test coverage
needed for those controls. It does not change Phase 0 containment, Phase 1 JWT,
OTP, password recovery, or session revocation behavior.

The reporting model is an effective-dated `manager_employee_scopes` table rather
than a `users.manager_id` column. This preserves reassignment history, permits an
employee to have no current manager, and leaves room for future reporting models
without introducing a team subsystem. Project membership is deliberately not
used as an HR reporting relationship.

Authorization failures use `403`. This application does not consistently hide
resource existence, so returning `404` for known-but-cross-scope records would
create a misleading mixed policy. Denials are audited without storing personal
record contents.

## Authorization inventory

The roles are `ADMIN`, `MANAGER`, and `EMPLOYEE`. Before Phase 2, the general
`MANAGER` role opened organization-wide leave, correction, attendance, daily-log,
directory, report, and export paths. Review methods did not prevent the request
subject from being the reviewer. Project financial visibility was also derived
from the global manager role rather than project authority.

Controller inventory and replacement:

| Area and endpoints | Gate at controller | Phase 2 domain enforcement |
| --- | --- | --- |
| Leave `POST`, `GET`, own balance, cancel | all authenticated roles | owner rules remain |
| Leave user balance, approve, reject | `ADMIN`/`MANAGER` | target must be in active manager scope; self-review denied |
| Leave balance mutation | `ADMIN` | organization-level admin operation only |
| Daily logs create, update, own list | authenticated roles | owner or scoped-manager edit; admin edit remains prohibited |
| Daily logs team list | `ADMIN`/`MANAGER` | repository-scoped for manager |
| Attendance own start/end/break/heartbeat/list | authenticated roles | owner-only behavior remains |
| Attendance user start/end and team reports | `ADMIN`/`MANAGER` | non-self scoped target for manager; admin override allowed |
| Corrections create/list | authenticated roles | owner create; manager list is repository-scoped |
| Correction approve/reject | `ADMIN`/`MANAGER` | scoped subject and reviewer separation |
| Users own profile/documents/preferences/sessions | authenticated roles | self only |
| Users directory | `ADMIN`/`MANAGER` | admin full DTO; manager reduced DTO and scoped query |
| User summary, overview, document mutation, record mutation, session revoke | `ADMIN` | remains administrator-only with service defense and audit |
| Manager-scope operations | `ADMIN` | controller and service checks; no manager delegation |
| Project create/update/delete, boards, milestones | role plus `ProjectAccessService` | project manager/admin, or explicit member permission where defined |
| Project payments and attachments | `ADMIN`/`MANAGER` plus project policy | project manager/admin only; ordinary manager member denied |
| Project notes | authenticated/project roles | existing project note capabilities retained |
| Attendance and log CSV exports | authenticated roles | same scoped repository sources as screen lists |
| Staff CSV export | `ADMIN` | remains administrator-only |
| Notifications | authenticated roles | repository methods are bound to authenticated user; no client target ID |

Endpoints accepting or deriving another subject identifier are attendance
`/users/{userId}/active/*`, leave `/users/{userId}/balance`, leave and correction
review IDs, daily-log IDs, user record/document/session IDs, manager-scope employee
and manager IDs, project manager/member IDs, task assignee IDs, and project/payment
resource IDs. Phase 2 routes employee HR targets through
`AuthorizationPolicyService`, while project and task targets use
`ProjectAccessService`. Reviewer identity always comes from `SecurityUtils`.

Employee-data response paths are the user directory and overview, leave,
attendance, corrections, daily logs, exports, project members/tasks, and
notifications. Manager directory responses now use `ManagerDirectoryResponse`;
entities are not serialized. HR documents and full staff overview/export remain
administrator-only (self-document listing is allowed).

Direct role checks that remain are domain restrictions, not grants of
organization-wide manager authority: admins cannot submit leave/logs; only admins
change leave balances and staff records; project managers may manage only their
own projects; users assigned to projects cannot be admins; and audit reason codes
distinguish administrator overrides from scoped-manager actions.

`ProjectAccessService` is used by project CRUD, boards, payments, notes,
milestones, and task create/read/update/comment/status operations. Its policies
now distinguish project access, project management, explicit task/note
capabilities, financial visibility, and payment mutation.

## Data model and invariants

`manager_employee_scopes` stores manager, employee, assigning administrator,
active/effective timestamps, audit timestamps, and an optimistic-lock version.
Foreign keys use `ON DELETE RESTRICT`. A partial unique index permits only one
active manager per employee. A check rejects self-management. Database triggers
validate active `MANAGER`/`EMPLOYEE`/`ADMIN` role combinations and end a scope if
either participant becomes inactive or changes to an incompatible role.

Assignment and reassignment lock the employee and manager rows, lock the current
active relationship, end the previous relationship, and insert the replacement
in one transaction. The database unique index is the final concurrent-write
guard. Repeating the same assignment is idempotent. Removing a missing assignment
is idempotent.

No assignments are seeded. After deployment, an administrator must populate
verified relationships through the scope API. Employees without an assignment
remain usable but are invisible to managers outside self/project-specific paths.

## Policy summary

- Employees see and mutate only their permitted self-owned HR records and
  assigned projects. They cannot enumerate the organization or review requests.
- Managers see reduced directory data, leave, attendance, corrections, and daily
  logs only for active scoped employees. They cannot review/override themselves.
- Administrators retain required organization-wide operations. Sensitive reads
  and overrides are audited.
- Ordinary project membership grants project visibility only. Financial fields
  are `null` unless the actor is the project manager or an administrator.
  Payment history and mutation use the same rule.
- Attendance/log exports call the same scoped services used by lists. Staff
  record export remains administrator-only.

## Scope administration API

- `PUT /api/v1/manager-scopes/employees/{employeeId}` with `managerId`
- `DELETE /api/v1/manager-scopes/employees/{employeeId}`
- `GET /api/v1/manager-scopes/employees/{employeeId}`
- `GET /api/v1/manager-scopes/managers/{managerId}/employees?page=&size=`
- `GET /api/v1/manager-scopes/managers`

All endpoints require `ADMIN`, and services repeat the authorization check.

## Deployment and rollback

1. Back up the Supabase PostgreSQL database and record Flyway state at V34.
2. Deploy the backend so Flyway applies V35 before manager traffic is enabled.
3. Populate reviewed reporting relationships through the administrator API.
4. Verify each manager sees only a small expected sample of assigned employees.
5. Attempt manager self-review and cross-scope review/attendance operations; expect
   controlled `403` responses.
6. Verify ordinary project members receive null financial fields and payment
   endpoints return `403`; verify project managers/admins retain required access.
7. Deploy the capability-aware frontend.
8. Monitor `security_audit_events` for denials, scope mutations, overrides,
   sensitive HR events, and payment events.
9. Prefer forward fixes. If an emergency rollback is required, roll application
   code back while retaining V35 (it is additive), or restore the predeployment
   backup. Do not drop the table while new code or audit evidence depends on it.

V35 is additive and preserves all users. Rolling the database backward would
discard relationship history, so a forward migration or backup restore is the
safe rollback mechanism.

## Deferred risks

Upload security, leave/attendance calculation correctness, broader approval
state-machine concurrency, email outbox delivery, large-table query tuning,
large-service refactoring, HttpOnly refresh-token architecture, and general
deployment hardening remain outside Phase 2. The scope and reviewer controls do
not by themselves establish complete production safety.
