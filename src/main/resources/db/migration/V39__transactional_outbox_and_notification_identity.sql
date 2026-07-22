CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    recipient_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    recipient_address_hash VARCHAR(64) NOT NULL,
    recipient_address_ciphertext BYTEA NOT NULL,
    template_key VARCHAR(80) NOT NULL,
    payload_ciphertext BYTEA NOT NULL,
    sensitive_payload BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    priority SMALLINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP,
    locked_by VARCHAR(120),
    sent_at TIMESTAMP,
    failed_at TIMESTAMP,
    last_error_code VARCHAR(80),
    last_error_summary VARCHAR(300),
    provider_message_id VARCHAR(200),
    idempotency_key VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_outbox_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PROCESSING','SENT','RETRY','FAILED','CANCELLED','EXPIRED','SUPPRESSED')),
    CONSTRAINT chk_outbox_attempts CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT chk_outbox_priority CHECK (priority BETWEEN -100 AND 100),
    CONSTRAINT uq_outbox_logical_delivery UNIQUE (event_id, event_type, recipient_address_hash, channel, template_key),
    CONSTRAINT uq_outbox_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE outbox_delivery_attempts (
    id BIGSERIAL PRIMARY KEY,
    outbox_message_id UUID NOT NULL REFERENCES outbox_messages(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(120) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    error_code VARCHAR(80),
    error_summary VARCHAR(300),
    provider_message_id VARCHAR(200),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_outbox_attempt_outcome CHECK (outcome IN ('ACCEPTED','RETRYABLE_FAILURE','PERMANENT_FAILURE','CANCELLED','EXPIRED')),
    CONSTRAINT uq_outbox_attempt_number UNIQUE (outbox_message_id, attempt_number)
);

CREATE INDEX idx_outbox_ready
    ON outbox_messages(priority DESC, available_at, created_at)
    WHERE status IN ('PENDING','RETRY');
CREATE INDEX idx_outbox_processing_stale
    ON outbox_messages(locked_at)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_outbox_failed
    ON outbox_messages(failed_at DESC)
    WHERE status = 'FAILED';
CREATE INDEX idx_outbox_event ON outbox_messages(event_id);
CREATE INDEX idx_outbox_retention ON outbox_messages(status, COALESCE(sent_at, failed_at, updated_at));
CREATE INDEX idx_outbox_attempts_message ON outbox_delivery_attempts(outbox_message_id, attempt_number DESC);

ALTER TABLE notifications
    ADD COLUMN event_id UUID,
    ADD COLUMN event_type VARCHAR(100);

CREATE UNIQUE INDEX uq_notifications_event_recipient
    ON notifications(event_id, user_id)
    WHERE event_id IS NOT NULL;
CREATE INDEX idx_notifications_event ON notifications(event_id)
    WHERE event_id IS NOT NULL;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_event_identity
    CHECK ((event_id IS NULL AND event_type IS NULL) OR (event_id IS NOT NULL AND event_type IS NOT NULL));

COMMENT ON COLUMN notifications.event_id IS
    'Stable identity for Phase 6+ logical events. NULL denotes a readable legacy notification.';
COMMENT ON COLUMN outbox_messages.payload_ciphertext IS
    'Application-encrypted minimum template payload; cleared when sensitive content reaches a terminal state.';

ALTER TABLE user_notification_settings
    ADD COLUMN email_attendance_updates BOOLEAN NOT NULL DEFAULT TRUE;
