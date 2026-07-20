CREATE TABLE manager_employee_scopes (
    id BIGSERIAL PRIMARY KEY,
    manager_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    employee_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ended_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_manager_scope_not_self CHECK (manager_user_id <> employee_user_id),
    CONSTRAINT chk_manager_scope_version_nonnegative CHECK (version >= 0),
    CONSTRAINT chk_manager_scope_end_state CHECK (
        (active = TRUE AND ended_at IS NULL)
        OR (active = FALSE AND ended_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_manager_employee_scopes_active_employee
    ON manager_employee_scopes(employee_user_id)
    WHERE active = TRUE;

CREATE INDEX idx_manager_employee_scopes_active_manager
    ON manager_employee_scopes(manager_user_id, employee_user_id)
    WHERE active = TRUE;

CREATE INDEX idx_manager_employee_scopes_employee_history
    ON manager_employee_scopes(employee_user_id, assigned_at DESC);

CREATE OR REPLACE FUNCTION validate_manager_employee_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    manager_role VARCHAR(50);
    manager_active BOOLEAN;
    employee_role VARCHAR(50);
    employee_active BOOLEAN;
    assigner_role VARCHAR(50);
    assigner_active BOOLEAN;
BEGIN
    IF NEW.active = FALSE THEN
        RETURN NEW;
    END IF;

    SELECT role, active INTO manager_role, manager_active
    FROM users WHERE id = NEW.manager_user_id;
    SELECT role, active INTO employee_role, employee_active
    FROM users WHERE id = NEW.employee_user_id;
    SELECT role, active INTO assigner_role, assigner_active
    FROM users WHERE id = NEW.assigned_by_user_id;

    IF manager_role IS DISTINCT FROM 'MANAGER' OR manager_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'active manager scope requires an active MANAGER';
    END IF;
    IF employee_role IS DISTINCT FROM 'EMPLOYEE' OR employee_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'active manager scope requires an active EMPLOYEE';
    END IF;
    IF assigner_role IS DISTINCT FROM 'ADMIN' OR assigner_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'manager scope must be assigned by an active ADMIN';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_manager_employee_scope
    BEFORE INSERT OR UPDATE OF manager_user_id, employee_user_id, assigned_by_user_id, active
    ON manager_employee_scopes
    FOR EACH ROW
    EXECUTE FUNCTION validate_manager_employee_scope();

CREATE OR REPLACE FUNCTION end_invalid_manager_employee_scopes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.active IS DISTINCT FROM TRUE OR NEW.role NOT IN ('MANAGER', 'EMPLOYEE') THEN
        UPDATE manager_employee_scopes
        SET active = FALSE,
            ended_at = COALESCE(ended_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP,
            version = version + 1
        WHERE active = TRUE
          AND (manager_user_id = NEW.id OR employee_user_id = NEW.id);
    ELSIF NEW.role = 'MANAGER' THEN
        UPDATE manager_employee_scopes
        SET active = FALSE,
            ended_at = COALESCE(ended_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP,
            version = version + 1
        WHERE active = TRUE AND employee_user_id = NEW.id;
    ELSIF NEW.role = 'EMPLOYEE' THEN
        UPDATE manager_employee_scopes
        SET active = FALSE,
            ended_at = COALESCE(ended_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP,
            version = version + 1
        WHERE active = TRUE AND manager_user_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_end_invalid_manager_employee_scopes
    AFTER UPDATE OF role, active ON users
    FOR EACH ROW
    WHEN (OLD.role IS DISTINCT FROM NEW.role OR OLD.active IS DISTINCT FROM NEW.active)
    EXECUTE FUNCTION end_invalid_manager_employee_scopes();

-- Reporting relationships are deliberately left empty. Production assignments
-- must be verified and created through the administrator-only scope API.
