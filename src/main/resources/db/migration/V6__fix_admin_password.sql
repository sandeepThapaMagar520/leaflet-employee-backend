-- Fix admin password to match "password" (BCrypt 12 rounds)
UPDATE users
SET password = '$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2'
WHERE email = 'admin@example.com';
