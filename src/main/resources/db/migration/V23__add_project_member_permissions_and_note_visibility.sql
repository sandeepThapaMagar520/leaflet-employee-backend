ALTER TABLE project_assignments
    ADD COLUMN can_manage_tasks BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN can_add_notes BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE project_notes SET note_type = 'TEAM' WHERE note_type = 'CLIENT';
UPDATE project_notes SET note_type = 'ADMIN_ONLY' WHERE note_type = 'INTERNAL';
