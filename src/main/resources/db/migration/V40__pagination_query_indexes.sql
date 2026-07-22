-- Evidence-backed indexes for stable, bounded Phase 7 page queries.
-- Each index includes the id tie-breaker used by the API's deterministic ordering.
CREATE INDEX idx_projects_created_page
    ON projects(created_at DESC, id DESC);

CREATE INDEX idx_tasks_created_page
    ON tasks(created_at DESC, id DESC);

CREATE INDEX idx_tasks_project_created_page
    ON tasks(project_id, created_at DESC, id DESC);

CREATE INDEX idx_attendance_sessions_start_page
    ON attendance_sessions(start_time DESC, id DESC);
