ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS budget_amount NUMERIC(14, 2) NOT NULL DEFAULT 0;

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS internal_notes TEXT;

CREATE TABLE IF NOT EXISTS project_payments (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL,
    reference_note TEXT,
    created_by_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_payments_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_payments_created_by FOREIGN KEY (created_by_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_project_payments_project_paid
    ON project_payments (project_id, paid_at DESC);
