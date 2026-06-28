ALTER TABLE attendance_sessions
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS break_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS break_minutes INTEGER NOT NULL DEFAULT 0;

UPDATE attendance_sessions
SET last_heartbeat_at = COALESCE(last_heartbeat_at, start_time)
WHERE end_time IS NULL;
