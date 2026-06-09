INSERT INTO users (full_name, email, password, role, active)
VALUES ('Super Admin', 'superadmin@ems.com', '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2', 'ADMIN', true)
ON CONFLICT (email) DO NOTHING;
