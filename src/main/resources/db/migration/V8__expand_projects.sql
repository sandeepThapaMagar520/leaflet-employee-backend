ALTER TABLE projects ADD COLUMN client_notes TEXT;
ALTER TABLE projects ADD COLUMN document_url VARCHAR(255);

CREATE TABLE project_assignments (
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_pa_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_pa_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
