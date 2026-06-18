ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS server_details TEXT;

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS domain_name VARCHAR(255);

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS annual_maintenance_cost NUMERIC(14, 2);
