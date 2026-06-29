CREATE TABLE IF NOT EXISTS app_settings (
    setting_key VARCHAR(120) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('leave.annual.days', '21'),
    ('leave.sick.days', '12'),
    ('leave.reset.month', '1'),
    ('attendance.required.minutes', '420'),
    ('attendance.grace.minutes', '360'),
    ('attendance.break.reminder.minutes', '30'),
    ('attendance.missing.checkout.minutes', '600'),
    ('attendance.heartbeat.stale.minutes', '10'),
    ('attendance.admin.override.enabled', 'true'),
    ('session.idle.timeout.minutes', '60'),
    ('session.warning.seconds', '60'),
    ('session.browser.only', 'true')
ON CONFLICT (setting_key) DO NOTHING;
