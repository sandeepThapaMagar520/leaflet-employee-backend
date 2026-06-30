ALTER TABLE users
    ADD COLUMN IF NOT EXISTS sick_leave_balance_adjustment_days INTEGER NOT NULL DEFAULT 0;
