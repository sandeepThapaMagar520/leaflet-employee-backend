ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_id VARCHAR(50);
ALTER TABLE users ADD COLUMN IF NOT EXISTS joining_date DATE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS employment_type VARCHAR(30) NOT NULL DEFAULT 'FULL_TIME';
ALTER TABLE users ADD COLUMN IF NOT EXISTS location VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS emergency_contact VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_employee_id
    ON users (employee_id)
    WHERE employee_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS staff_documents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_type VARCHAR(40) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_url VARCHAR(1000) NOT NULL,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_staff_documents_user_id ON staff_documents(user_id);

CREATE TABLE IF NOT EXISTS staff_audit_events (
    id BIGSERIAL PRIMARY KEY,
    staff_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_user_id BIGINT REFERENCES users(id),
    action VARCHAR(40) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_staff_audit_events_staff_user_id ON staff_audit_events(staff_user_id);
