CREATE TABLE IF NOT EXISTS user_notification_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email_task_assigned BOOLEAN NOT NULL DEFAULT true,
    email_task_completed BOOLEAN NOT NULL DEFAULT true,
    email_task_commented BOOLEAN NOT NULL DEFAULT false,
    email_task_due_soon BOOLEAN NOT NULL DEFAULT true,
    email_task_overdue BOOLEAN NOT NULL DEFAULT true,
    email_project_assigned BOOLEAN NOT NULL DEFAULT true,
    email_leave_updates BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO user_notification_settings (user_id)
SELECT id FROM users
ON CONFLICT (user_id) DO NOTHING;
