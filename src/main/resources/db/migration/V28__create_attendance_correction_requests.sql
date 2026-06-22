CREATE TABLE IF NOT EXISTS attendance_correction_requests (
    id BIGSERIAL PRIMARY KEY,
    attendance_session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    original_start_time TIMESTAMP NOT NULL,
    original_end_time TIMESTAMP,
    requested_start_time TIMESTAMP NOT NULL,
    requested_end_time TIMESTAMP NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT,
    reviewer_note TEXT,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_attendance_correction_session FOREIGN KEY (attendance_session_id) REFERENCES attendance_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_correction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_correction_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id),
    CONSTRAINT chk_attendance_correction_times CHECK (requested_end_time > requested_start_time)
);

CREATE INDEX IF NOT EXISTS idx_attendance_correction_user_created
    ON attendance_correction_requests(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_attendance_correction_status
    ON attendance_correction_requests(status);
