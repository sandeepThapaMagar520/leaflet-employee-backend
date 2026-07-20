-- READ-ONLY PHASE 4 PREDEPLOYMENT VALIDATION.
-- Run against a restored disposable production backup first, then production
-- before deploying V37. The statements intentionally do not repair data.

-- Duplicate open attendance sessions.
SELECT user_id, count(*) AS active_count, array_agg(id ORDER BY start_time, id) AS session_ids
FROM attendance_sessions
WHERE end_time IS NULL
GROUP BY user_id
HAVING count(*) > 1;

-- Project board names are already looked up case-insensitively by the service.
SELECT project_id, lower(btrim(name)) AS normalized_name,
       count(*) AS board_count, array_agg(id ORDER BY id) AS board_ids
FROM project_task_boards
GROUP BY project_id, lower(btrim(name))
HAVING count(*) > 1;

-- Duplicate employee IDs. Existing service lookup is case-insensitive, so V37
-- enforces that already-established semantic at the database boundary.
SELECT lower(btrim(employee_id)) AS normalized_employee_id,
       count(*) AS claim_count,
       array_agg(id ORDER BY id) AS user_ids
FROM users
WHERE employee_id IS NOT NULL
GROUP BY lower(btrim(employee_id))
HAVING count(*) > 1;

-- Duplicate pending corrections for one attendance session.
SELECT attendance_session_id, count(*) AS pending_count, array_agg(id ORDER BY created_at, id) AS correction_ids
FROM attendance_correction_requests
WHERE status = 'PENDING'
GROUP BY attendance_session_id
HAVING count(*) > 1;

-- Duplicate normalized current/pending email identities.
SELECT lower(btrim(identity_value)) AS normalized_identity,
       array_agg(user_id || ':' || claim_type ORDER BY user_id, claim_type) AS claims
FROM (
    SELECT id AS user_id, 'EMAIL' AS claim_type, email AS identity_value FROM users
    UNION ALL
    SELECT id, 'PENDING_EMAIL', pending_email FROM users WHERE pending_email IS NOT NULL
) identities
GROUP BY lower(btrim(identity_value))
HAVING count(*) > 1;

-- Structurally invalid project/payment values.
SELECT id, budget_amount, start_date, due_date, name
FROM projects
WHERE budget_amount < 0
   OR (start_date IS NOT NULL AND due_date IS NOT NULL AND due_date < start_date)
   OR btrim(name) = '';
SELECT id, project_id, amount FROM project_payments WHERE amount <= 0;
SELECT id, project_id, name, status_key, display_order
FROM project_task_boards
WHERE btrim(name) = '' OR btrim(status_key) = '' OR display_order < 0;

-- Invalid attendance values.
SELECT id, user_id, start_time, end_time, break_minutes, total_hours
FROM attendance_sessions
WHERE (end_time IS NOT NULL AND end_time <= start_time)
   OR break_minutes < 0
   OR total_hours < 0
   OR (
       end_time IS NOT NULL
       AND break_minutes > floor(extract(epoch FROM (end_time - start_time)) / 60)
   );
SELECT id, attendance_session_id, requested_start_time, requested_end_time
FROM attendance_correction_requests
WHERE requested_end_time - requested_start_time > interval '24 hours';

-- Overlapping attendance ranges. Endpoints use half-open [start,end) ranges.
SELECT first_session.user_id,
       first_session.id AS first_session_id,
       second_session.id AS second_session_id,
       first_session.start_time AS first_start,
       first_session.end_time AS first_end,
       second_session.start_time AS second_start,
       second_session.end_time AS second_end
FROM attendance_sessions first_session
JOIN attendance_sessions second_session
  ON first_session.user_id = second_session.user_id
 AND first_session.id < second_session.id
 AND first_session.start_time < COALESCE(second_session.end_time, 'infinity'::timestamp)
 AND second_session.start_time < COALESCE(first_session.end_time, 'infinity'::timestamp)
ORDER BY first_session.user_id, first_session.start_time;

-- Invalid approval/reviewer states.
SELECT id, status, reviewer_id, reviewed_at
FROM leave_requests
WHERE (
    status IN ('PENDING', 'CANCELLED')
    AND (reviewer_id IS NOT NULL OR reviewed_at IS NOT NULL)
) OR (
    status IN ('APPROVED', 'REJECTED')
    AND (reviewer_id IS NULL OR reviewed_at IS NULL)
);
SELECT id, status, reviewer_id, reviewed_at
FROM attendance_correction_requests
WHERE (
    status = 'PENDING'
    AND (reviewer_id IS NOT NULL OR reviewed_at IS NOT NULL)
) OR (
    status IN ('APPROVED', 'REJECTED')
    AND (reviewer_id IS NULL OR reviewed_at IS NULL)
);

-- Invalid controlled values.
SELECT id, role, employment_type FROM users
WHERE role NOT IN ('ADMIN', 'MANAGER', 'EMPLOYEE')
   OR employment_type NOT IN ('FULL_TIME', 'PART_TIME', 'CONTRACTOR', 'INTERN');
SELECT id, status FROM projects
WHERE status NOT IN ('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED');
SELECT id, status, priority FROM tasks
WHERE btrim(status) = '' OR priority NOT IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
SELECT id, leave_type, status FROM leave_requests
WHERE leave_type NOT IN ('ANNUAL', 'SICK', 'PERSONAL', 'UNPAID')
   OR status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');
SELECT id, status FROM attendance_correction_requests
WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED');
SELECT id, type FROM notifications
WHERE type NOT IN (
    'TASK_ASSIGNED', 'TASK_COMMENTED', 'TASK_COMPLETED',
    'TASK_DUE_SOON', 'TASK_OVERDUE', 'PROJECT_ASSIGNED', 'SYSTEM'
);
SELECT id, purpose, status, scanning_status FROM media_assets
WHERE purpose NOT IN (
    'PROFILE_IMAGE', 'PROJECT_ATTACHMENT', 'TASK_ATTACHMENT',
    'PAYMENT_ATTACHMENT', 'HR_DOCUMENT'
) OR status NOT IN (
    'PENDING', 'QUARANTINED', 'VERIFIED', 'REJECTED', 'ATTACHED', 'DELETED'
) OR scanning_status NOT IN (
    'NOT_REQUIRED', 'PENDING', 'CLEAN', 'MALWARE_DETECTED', 'FAILED', 'UNAVAILABLE'
);

-- Existing FK constraints protect the principal relationships. This diagnostic
-- identifies any constraint that was created NOT VALID and still needs review.
SELECT conrelid::regclass AS table_name, conname, contype, convalidated
FROM pg_constraint
WHERE contype IN ('f', 'c', 'u')
  AND NOT convalidated
ORDER BY table_name::text, conname;

-- Defensive orphan report for the principal workflow relationships. With
-- validated foreign keys this remains empty; a nonempty result indicates
-- constraints were disabled or imported data bypassed the normal schema.
SELECT 'attendance_sessions.user_id' AS relationship, attendance.id AS row_id
FROM attendance_sessions attendance
LEFT JOIN users owner ON owner.id = attendance.user_id
WHERE owner.id IS NULL
UNION ALL
SELECT 'attendance_corrections.session_id', correction.id
FROM attendance_correction_requests correction
LEFT JOIN attendance_sessions session
  ON session.id = correction.attendance_session_id
WHERE session.id IS NULL
UNION ALL
SELECT 'leave_requests.user_id', leave_row.id
FROM leave_requests leave_row
LEFT JOIN users owner ON owner.id = leave_row.user_id
WHERE owner.id IS NULL
UNION ALL
SELECT 'project_payments.project_id', payment.id
FROM project_payments payment
LEFT JOIN projects project ON project.id = payment.project_id
WHERE project.id IS NULL;

-- Version columns do not exist at V36. This block is safe both before and
-- after V37; after V37 it warns if a database-level bypass created bad data.
DO $$
DECLARE
    invalid_versions BIGINT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'attendance_sessions' AND column_name = 'version'
    ) THEN
        EXECUTE $query$
            SELECT count(*) FROM (
                SELECT version FROM attendance_sessions WHERE version < 0
                UNION ALL SELECT version FROM attendance_correction_requests WHERE version < 0
                UNION ALL SELECT version FROM leave_requests WHERE version < 0
                UNION ALL SELECT version FROM projects WHERE version < 0
                UNION ALL SELECT version FROM project_payments WHERE version < 0
                UNION ALL SELECT version FROM tasks WHERE version < 0
                UNION ALL SELECT version FROM project_task_boards WHERE version < 0
            ) invalid
        $query$ INTO invalid_versions;
        IF invalid_versions > 0 THEN
            RAISE WARNING 'Phase 4 validation found % negative version values', invalid_versions;
        END IF;
    END IF;
END $$;

-- Remediation is manual and reviewed:
-- 1. Establish the authoritative business record with HR/operations.
-- 2. Preserve every superseded row and decision in an incident/change record.
-- 3. Do not delete sessions, merge accounts, rewrite money, or choose an
--    approval outcome automatically.
-- 4. Re-run this entire file until every result set is empty.
