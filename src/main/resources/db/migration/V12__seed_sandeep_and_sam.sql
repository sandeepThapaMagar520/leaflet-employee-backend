-- Seed Sandeep as ADMIN and Sam as EMPLOYEE
-- BCrypt hash below is for plaintext password "password" (12 rounds)

INSERT INTO users (full_name, email, password, role, active)
VALUES ('Sandeep', 'sandeep@gmail.com', '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2', 'ADMIN', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO users (full_name, email, password, role, active)
VALUES ('Sam', 'sam@gmail.com', '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2', 'EMPLOYEE', true)
ON CONFLICT (email) DO NOTHING;
