WITH ranked_logs AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id, log_date
            ORDER BY updated_at DESC, created_at DESC, id DESC
        ) AS row_number
    FROM daily_logs
)
DELETE FROM daily_logs
WHERE id IN (
    SELECT id
    FROM ranked_logs
    WHERE row_number > 1
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_daily_logs_user_date'
    ) THEN
        ALTER TABLE daily_logs
            ADD CONSTRAINT uk_daily_logs_user_date UNIQUE (user_id, log_date);
    END IF;
END $$;
