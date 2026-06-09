-- Same BCrypt hash as V6/V7: plaintext password is "password"
INSERT INTO users (full_name, email, password, role, active)
VALUES ('Demo Employee', 'employee@example.com', '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2', 'EMPLOYEE', true)
ON CONFLICT (email) DO NOTHING;
