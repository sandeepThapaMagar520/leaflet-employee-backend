CREATE TABLE auth_rate_limit_buckets (
    rate_key VARCHAR(160) PRIMARY KEY,
    window_started_at TIMESTAMP NOT NULL,
    event_count INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_auth_rate_limit_bucket_count_positive CHECK (event_count > 0)
);

CREATE INDEX idx_auth_rate_limit_buckets_updated
    ON auth_rate_limit_buckets(updated_at);

-- The legacy event rows are no longer read after V42. Retain the table for a
-- backward-compatible application rollback, while removing expired history.
DELETE FROM auth_rate_limit_events
WHERE attempted_at < CURRENT_TIMESTAMP - INTERVAL '24 hours';
