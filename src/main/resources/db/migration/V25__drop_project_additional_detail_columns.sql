ALTER TABLE projects
    DROP COLUMN IF EXISTS server_details;

ALTER TABLE projects
    DROP COLUMN IF EXISTS domain_name;

ALTER TABLE projects
    DROP COLUMN IF EXISTS annual_maintenance_cost;
