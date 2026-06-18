CREATE TABLE IF NOT EXISTS project_task_boards (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    status_key VARCHAR(80) NOT NULL,
    name VARCHAR(255) NOT NULL,
    display_order INTEGER NOT NULL,
    default_board BOOLEAN NOT NULL DEFAULT FALSE,
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_task_boards_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT uk_project_task_boards_project_key UNIQUE (project_id, status_key),
    CONSTRAINT uk_project_task_boards_project_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS idx_project_task_boards_project_id ON project_task_boards(project_id);

INSERT INTO project_task_boards (project_id, status_key, name, display_order, default_board, terminal)
SELECT p.id, board.status_key, board.name, board.display_order, TRUE, board.terminal
FROM projects p
CROSS JOIN (
    VALUES
        ('TODO', 'To Do', 10, FALSE),
        ('IN_PROGRESS', 'In Progress', 20, FALSE),
        ('BLOCKED', 'Blocked', 30, FALSE),
        ('DONE', 'Done', 40, TRUE)
) AS board(status_key, name, display_order, terminal)
ON CONFLICT (project_id, status_key) DO NOTHING;
