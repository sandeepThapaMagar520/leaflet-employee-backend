-- Phase 5: domain policy backstops. This migration fails instead of guessing how
-- to merge overlapping leave requests.
DO $$
DECLARE
    sample_pairs TEXT;
BEGIN
    SELECT string_agg(first_request.id::text || ':' || second_request.id::text, ', ')
    INTO sample_pairs
    FROM leave_requests first_request
    JOIN leave_requests second_request
      ON first_request.user_id = second_request.user_id
     AND first_request.id < second_request.id
     AND first_request.status IN ('PENDING', 'APPROVED')
     AND second_request.status IN ('PENDING', 'APPROVED')
     AND first_request.start_date <= second_request.end_date
     AND second_request.start_date <= first_request.end_date;

    IF sample_pairs IS NOT NULL THEN
        RAISE EXCEPTION
            'V38 blocked: overlapping pending/approved leave requests exist (request pairs: %). Resolve explicitly before retrying.',
            sample_pairs;
    END IF;
END
$$;

CREATE TABLE company_holidays (
    holiday_date DATE PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_company_holiday_name_nonblank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_company_holidays_active_date
    ON company_holidays(holiday_date)
    WHERE active = TRUE;

ALTER TABLE project_payments
    ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_project_payments_project_idempotency
    ON project_payments(project_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE leave_requests
    ADD CONSTRAINT ex_leave_requests_no_active_overlap
    EXCLUDE USING gist (
        user_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
    WHERE (status IN ('PENDING', 'APPROVED'));
