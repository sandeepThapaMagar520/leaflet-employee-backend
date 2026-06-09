ALTER TABLE daily_logs DROP COLUMN hours_spent;
ALTER TABLE daily_logs RENAME COLUMN description TO summary;
ALTER TABLE daily_logs ADD COLUMN problems_faced TEXT;
