-- Phase 4 is intentionally fail-fast. No historical attendance, identity,
-- financial, approval, or timestamp data is silently rewritten.
DO $$
DECLARE
    sample_ids TEXT;
BEGIN
    SELECT string_agg(id::text, ', ' ORDER BY id)
    INTO sample_ids
    FROM (
        SELECT id
        FROM attendance_sessions
        WHERE end_time IS NULL
          AND user_id IN (
              SELECT user_id
              FROM attendance_sessions
              WHERE end_time IS NULL
              GROUP BY user_id
              HAVING COUNT(*) > 1
          )
        ORDER BY id
        LIMIT 20
    ) duplicates;
    IF sample_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'V37 blocked: duplicate active attendance sessions exist. Sample session ids: %. Run the Phase 4 dirty-data runbook.',
            sample_ids;
    END IF;

    SELECT string_agg(id::text, ', ' ORDER BY id)
    INTO sample_ids
    FROM (
        SELECT id
        FROM attendance_correction_requests
        WHERE status = 'PENDING'
          AND attendance_session_id IN (
              SELECT attendance_session_id
              FROM attendance_correction_requests
              WHERE status = 'PENDING'
              GROUP BY attendance_session_id
              HAVING COUNT(*) > 1
          )
        ORDER BY id
        LIMIT 20
    ) duplicates;
    IF sample_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'V37 blocked: duplicate pending attendance corrections exist. Sample correction ids: %. Run the Phase 4 dirty-data runbook.',
            sample_ids;
    END IF;

    SELECT string_agg(normalized_identity, ', ' ORDER BY normalized_identity)
    INTO sample_ids
    FROM (
        SELECT lower(btrim(identity_value)) AS normalized_identity
        FROM (
            SELECT email AS identity_value FROM users
            UNION ALL
            SELECT pending_email FROM users WHERE pending_email IS NOT NULL
        ) identities
        GROUP BY lower(btrim(identity_value))
        HAVING COUNT(*) > 1
        ORDER BY normalized_identity
        LIMIT 20
    ) duplicates;
    IF sample_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'V37 blocked: duplicate normalized email or pending-email identities exist: %. Run the Phase 4 dirty-data runbook.',
            sample_ids;
    END IF;

    SELECT string_agg(normalized_employee_id, ', ' ORDER BY normalized_employee_id)
    INTO sample_ids
    FROM (
        SELECT lower(btrim(employee_id)) AS normalized_employee_id
        FROM users
        WHERE employee_id IS NOT NULL
        GROUP BY lower(btrim(employee_id))
        HAVING COUNT(*) > 1
        ORDER BY normalized_employee_id
        LIMIT 20
    ) duplicates;
    IF sample_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'V37 blocked: duplicate case-insensitive employee ids exist: %. Run the Phase 4 dirty-data runbook.',
            sample_ids;
    END IF;

    SELECT string_agg(project_id::text || ':' || normalized_name, ', ' ORDER BY project_id, normalized_name)
    INTO sample_ids
    FROM (
        SELECT project_id, lower(btrim(name)) AS normalized_name
        FROM project_task_boards
        GROUP BY project_id, lower(btrim(name))
        HAVING COUNT(*) > 1
        ORDER BY project_id, normalized_name
        LIMIT 20
    ) duplicates;
    IF sample_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'V37 blocked: duplicate case-insensitive project board names exist: %. Run the Phase 4 dirty-data runbook.',
            sample_ids;
    END IF;

    IF EXISTS (
        SELECT 1 FROM projects
        WHERE budget_amount < 0
           OR (start_date IS NOT NULL AND due_date IS NOT NULL AND due_date < start_date)
           OR btrim(name) = ''
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: projects contain negative budgets, invalid date ranges, or blank names. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (SELECT 1 FROM project_payments WHERE amount <= 0) THEN
        RAISE EXCEPTION
            'V37 blocked: project payments contain nonpositive amounts. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM project_task_boards
        WHERE btrim(name) = '' OR btrim(status_key) = '' OR display_order < 0
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: project boards contain blank identifiers or negative display order. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attendance_sessions
        WHERE end_time IS NOT NULL AND end_time <= start_time
           OR break_minutes < 0
           OR total_hours < 0
           OR (
               end_time IS NOT NULL
               AND break_minutes > floor(extract(epoch FROM (end_time - start_time)) / 60)
           )
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: attendance sessions contain invalid time, break, or total values. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM attendance_correction_requests
        WHERE requested_end_time - requested_start_time > interval '24 hours'
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: attendance corrections exceed the existing 24-hour limit. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attendance_sessions first_session
        JOIN attendance_sessions second_session
          ON first_session.user_id = second_session.user_id
         AND first_session.id < second_session.id
         AND first_session.start_time < COALESCE(second_session.end_time, 'infinity'::timestamp)
         AND second_session.start_time < COALESCE(first_session.end_time, 'infinity'::timestamp)
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: overlapping attendance sessions exist. Run the Phase 4 dirty-data runbook; no sessions were changed.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM users
        WHERE btrim(email) = ''
           OR role NOT IN ('ADMIN', 'MANAGER', 'EMPLOYEE')
           OR employment_type NOT IN ('FULL_TIME', 'PART_TIME', 'CONTRACTOR', 'INTERN')
    ) OR EXISTS (
        SELECT 1 FROM projects
        WHERE status NOT IN ('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED')
    ) OR EXISTS (
        SELECT 1 FROM tasks
        WHERE priority NOT IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
           OR btrim(status) = ''
           OR btrim(title) = ''
    ) OR EXISTS (
        SELECT 1 FROM leave_requests
        WHERE leave_type NOT IN ('ANNUAL', 'SICK', 'PERSONAL', 'UNPAID')
           OR status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
    ) OR EXISTS (
        SELECT 1 FROM attendance_correction_requests
        WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED')
    ) OR EXISTS (
        SELECT 1 FROM notifications
        WHERE type NOT IN (
            'TASK_ASSIGNED', 'TASK_COMMENTED', 'TASK_COMPLETED',
            'TASK_DUE_SOON', 'TASK_OVERDUE', 'PROJECT_ASSIGNED', 'SYSTEM'
        )
    ) OR EXISTS (
        SELECT 1 FROM staff_documents
        WHERE document_type NOT IN (
            'CONTRACT', 'ID_PROOF', 'RESUME', 'OFFER_LETTER', 'CERTIFICATE', 'OTHER'
        )
    ) OR EXISTS (
        SELECT 1 FROM media_assets
        WHERE purpose NOT IN (
            'PROFILE_IMAGE', 'PROJECT_ATTACHMENT', 'TASK_ATTACHMENT',
            'PAYMENT_ATTACHMENT', 'HR_DOCUMENT'
        )
           OR status NOT IN (
               'PENDING', 'QUARANTINED', 'VERIFIED', 'REJECTED', 'ATTACHED', 'DELETED'
           )
           OR scanning_status NOT IN (
               'NOT_REQUIRED', 'PENDING', 'CLEAN', 'MALWARE_DETECTED',
               'FAILED', 'UNAVAILABLE'
           )
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: an invalid controlled value or blank required identifier exists. Run the Phase 4 dirty-data runbook.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM leave_requests
        WHERE (
            status IN ('PENDING', 'CANCELLED')
            AND (reviewer_id IS NOT NULL OR reviewed_at IS NOT NULL)
        ) OR (
            status IN ('APPROVED', 'REJECTED')
            AND (reviewer_id IS NULL OR reviewed_at IS NULL)
        )
    ) OR EXISTS (
        SELECT 1 FROM attendance_correction_requests
        WHERE (
            status = 'PENDING'
            AND (reviewer_id IS NOT NULL OR reviewed_at IS NOT NULL)
        ) OR (
            status IN ('APPROVED', 'REJECTED')
            AND (reviewer_id IS NULL OR reviewed_at IS NULL)
        )
    ) THEN
        RAISE EXCEPTION
            'V37 blocked: approval rows have inconsistent reviewer state. Run the Phase 4 dirty-data runbook.';
    END IF;
END $$;

ALTER TABLE attendance_sessions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_attendance_session_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_attendance_session_time_order CHECK (
        end_time IS NULL OR end_time > start_time
    ),
    ADD CONSTRAINT chk_attendance_session_break_nonnegative CHECK (break_minutes >= 0),
    ADD CONSTRAINT chk_attendance_session_total_nonnegative CHECK (
        total_hours IS NULL OR total_hours >= 0
    ),
    ADD CONSTRAINT chk_attendance_session_break_within_duration CHECK (
        end_time IS NULL
        OR break_minutes <= floor(extract(epoch FROM (end_time - start_time)) / 60)
    );

ALTER TABLE attendance_correction_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_attendance_correction_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_attendance_correction_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED')
    ),
    ADD CONSTRAINT chk_attendance_correction_max_duration CHECK (
        requested_end_time - requested_start_time <= interval '24 hours'
    ),
    ADD CONSTRAINT chk_attendance_correction_review_state CHECK (
        (status = 'PENDING' AND reviewer_id IS NULL AND reviewed_at IS NULL)
        OR
        (status IN ('APPROVED', 'REJECTED') AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)
    );

ALTER TABLE leave_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_leave_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_leave_type_values CHECK (
        leave_type IN ('ANNUAL', 'SICK', 'PERSONAL', 'UNPAID')
    ),
    ADD CONSTRAINT chk_leave_status_values CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    ADD CONSTRAINT chk_leave_review_state CHECK (
        (status IN ('PENDING', 'CANCELLED') AND reviewer_id IS NULL AND reviewed_at IS NULL)
        OR
        (status IN ('APPROVED', 'REJECTED') AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)
    );

ALTER TABLE projects
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_project_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_project_budget_nonnegative CHECK (budget_amount >= 0),
    ADD CONSTRAINT chk_project_date_order CHECK (
        start_date IS NULL OR due_date IS NULL OR due_date >= start_date
    ),
    ADD CONSTRAINT chk_project_name_nonblank CHECK (btrim(name) <> ''),
    ADD CONSTRAINT chk_project_status_values CHECK (
        status IN ('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED')
    );

ALTER TABLE project_payments
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_project_payment_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_project_payment_amount_positive CHECK (amount > 0);

ALTER TABLE tasks
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_task_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_task_title_nonblank CHECK (btrim(title) <> ''),
    ADD CONSTRAINT chk_task_status_nonblank CHECK (btrim(status) <> ''),
    ADD CONSTRAINT chk_task_priority_values CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    );

ALTER TABLE project_task_boards
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_project_board_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_project_board_name_nonblank CHECK (btrim(name) <> ''),
    ADD CONSTRAINT chk_project_board_status_nonblank CHECK (btrim(status_key) <> ''),
    ADD CONSTRAINT chk_project_board_display_order_nonnegative CHECK (display_order >= 0);

ALTER TABLE users
    ADD CONSTRAINT chk_users_role_values CHECK (role IN ('ADMIN', 'MANAGER', 'EMPLOYEE')),
    ADD CONSTRAINT chk_users_employment_type_values CHECK (
        employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACTOR', 'INTERN')
    ),
    ADD CONSTRAINT chk_users_email_nonblank CHECK (btrim(email) <> '');

ALTER TABLE notifications
    ADD CONSTRAINT chk_notification_type_values CHECK (
        type IN (
            'TASK_ASSIGNED', 'TASK_COMMENTED', 'TASK_COMPLETED',
            'TASK_DUE_SOON', 'TASK_OVERDUE', 'PROJECT_ASSIGNED', 'SYSTEM'
        )
    );

ALTER TABLE staff_documents
    ADD CONSTRAINT chk_staff_document_type_values CHECK (
        document_type IN (
            'CONTRACT', 'ID_PROOF', 'RESUME', 'OFFER_LETTER', 'CERTIFICATE', 'OTHER'
        )
    );

ALTER TABLE media_assets
    ADD CONSTRAINT chk_media_purpose_values CHECK (
        purpose IN (
            'PROFILE_IMAGE', 'PROJECT_ATTACHMENT', 'TASK_ATTACHMENT',
            'PAYMENT_ATTACHMENT', 'HR_DOCUMENT'
        )
    ),
    ADD CONSTRAINT chk_media_status_values CHECK (
        status IN ('PENDING', 'QUARANTINED', 'VERIFIED', 'REJECTED', 'ATTACHED', 'DELETED')
    ),
    ADD CONSTRAINT chk_media_scanning_values CHECK (
        scanning_status IN (
            'NOT_REQUIRED', 'PENDING', 'CLEAN', 'MALWARE_DETECTED',
            'FAILED', 'UNAVAILABLE'
        )
    );

CREATE UNIQUE INDEX uq_attendance_sessions_one_active_user
    ON attendance_sessions(user_id)
    WHERE end_time IS NULL;

CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE attendance_sessions
    ADD CONSTRAINT ex_attendance_sessions_no_overlap
    EXCLUDE USING gist (
        user_id WITH =,
        tsrange(start_time, COALESCE(end_time, 'infinity'::timestamp), '[)') WITH &&
    );

CREATE UNIQUE INDEX uq_attendance_corrections_one_pending_session
    ON attendance_correction_requests(attendance_session_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX uq_users_email_ci ON users(lower(btrim(email)));
CREATE UNIQUE INDEX uq_users_pending_email_ci
    ON users(lower(btrim(pending_email)))
    WHERE pending_email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_employee_id_ci
    ON users(lower(btrim(employee_id)))
    WHERE employee_id IS NOT NULL;
CREATE UNIQUE INDEX uq_project_task_boards_name_ci
    ON project_task_boards(project_id, lower(btrim(name)));

-- Remove exact-case indexes superseded by the stricter normalized indexes.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
DROP INDEX IF EXISTS idx_users_pending_email;
DROP INDEX IF EXISTS idx_users_employee_id;
ALTER TABLE project_task_boards
    DROP CONSTRAINT IF EXISTS uk_project_task_boards_project_name;

-- This registry makes email and pending-email claims mutually exclusive even
-- across concurrent direct SQL writes to different users.
CREATE TABLE user_identity_claims (
    normalized_identity VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    claim_type VARCHAR(20) NOT NULL,
    CONSTRAINT uq_user_identity_claim_type UNIQUE(user_id, claim_type),
    CONSTRAINT chk_user_identity_claim_type CHECK (claim_type IN ('EMAIL', 'PENDING_EMAIL')),
    CONSTRAINT chk_user_identity_normalized CHECK (
        normalized_identity = lower(btrim(normalized_identity))
        AND normalized_identity <> ''
    )
);

INSERT INTO user_identity_claims(normalized_identity, user_id, claim_type)
SELECT lower(btrim(email)), id, 'EMAIL' FROM users;
INSERT INTO user_identity_claims(normalized_identity, user_id, claim_type)
SELECT lower(btrim(pending_email)), id, 'PENDING_EMAIL'
FROM users
WHERE pending_email IS NOT NULL;

CREATE OR REPLACE FUNCTION normalize_and_sync_user_identity_claims()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'DELETE' THEN
        NEW.email := lower(btrim(NEW.email));
        NEW.pending_email := NULLIF(lower(btrim(NEW.pending_email)), '');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_normalize_user_identities
    BEFORE INSERT OR UPDATE OF email, pending_email ON users
    FOR EACH ROW
    EXECUTE FUNCTION normalize_and_sync_user_identity_claims();

CREATE OR REPLACE FUNCTION sync_user_identity_claims()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM user_identity_claims WHERE user_id = OLD.id;
        RETURN OLD;
    END IF;

    DELETE FROM user_identity_claims
    WHERE user_id = NEW.id
      AND claim_type IN ('EMAIL', 'PENDING_EMAIL');

    INSERT INTO user_identity_claims(normalized_identity, user_id, claim_type)
    VALUES (lower(btrim(NEW.email)), NEW.id, 'EMAIL');

    IF NEW.pending_email IS NOT NULL THEN
        INSERT INTO user_identity_claims(normalized_identity, user_id, claim_type)
        VALUES (lower(btrim(NEW.pending_email)), NEW.id, 'PENDING_EMAIL');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_user_identity_claims_write
    AFTER INSERT OR UPDATE OF email, pending_email ON users
    FOR EACH ROW
    EXECUTE FUNCTION sync_user_identity_claims();

CREATE TRIGGER trg_sync_user_identity_claims_delete
    AFTER DELETE ON users
    FOR EACH ROW
    EXECUTE FUNCTION sync_user_identity_claims();

CREATE INDEX idx_projects_manager_id ON projects(manager_id);
CREATE INDEX idx_project_assignments_user_project
    ON project_assignments(user_id, project_id);
CREATE INDEX idx_attendance_sessions_user_time_range
    ON attendance_sessions(user_id, start_time DESC, end_time);
DROP INDEX IF EXISTS idx_attendance_user_id;
CREATE INDEX idx_attendance_corrections_pending_queue
    ON attendance_correction_requests(created_at, attendance_session_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_tasks_status_due
    ON tasks(status, due_date)
    WHERE due_date IS NOT NULL;
CREATE INDEX idx_leave_requests_user_status_dates
    ON leave_requests(user_id, status, start_date, end_date);
CREATE INDEX idx_leave_requests_pending_queue
    ON leave_requests(created_at, user_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_security_audit_events_actor_created
    ON security_audit_events(actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;
CREATE INDEX idx_security_audit_events_created
    ON security_audit_events(created_at DESC);
