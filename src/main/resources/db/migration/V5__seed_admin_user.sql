INSERT INTO users (full_name, email, password, role)
SELECT 'System Admin', 'admin@example.com', '$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/zBsqquWbHqgV.1Vv8aR10Vq6V.', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@example.com');
