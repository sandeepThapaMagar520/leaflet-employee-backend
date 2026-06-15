CREATE TABLE IF NOT EXISTS project_payment_attachments (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    file_url VARCHAR(1000) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_attachments_payment
        FOREIGN KEY (payment_id) REFERENCES project_payments (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_payment_attachments_payment
    ON project_payment_attachments (payment_id);
