CREATE TABLE IF NOT EXISTS project_notes (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    note_type VARCHAR(20) NOT NULL, -- 'CLIENT' or 'INTERNAL'
    created_by_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_notes_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_notes_created_by FOREIGN KEY (created_by_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_project_notes_project_type
    ON project_notes (project_id, note_type);
