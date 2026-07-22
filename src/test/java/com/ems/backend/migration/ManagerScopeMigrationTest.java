package com.ems.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("databaseAvailable")
class ManagerScopeMigrationTest {
    private static PostgreSQLContainer<?> postgres;

    static boolean databaseAvailable() {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        return (externalUrl != null && !externalUrl.isBlank())
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void cleanMigrationCreatesConstrainedEmptyScopeModel() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        assertEquals("40", flyway.info().current().getVersion().getVersion());
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM manager_employee_scopes"));

        long adminId = insertUser("scope-admin@example.test", "ADMIN");
        long managerId = insertUser("scope-manager@example.test", "MANAGER");
        long otherManagerId = insertUser("scope-manager-2@example.test", "MANAGER");
        long employeeId = insertUser("scope-employee@example.test", "EMPLOYEE");

        insertScope(managerId, employeeId, adminId);
        assertEquals(1, scalarInt("""
                SELECT COUNT(*) FROM manager_employee_scopes
                WHERE manager_user_id = %d AND employee_user_id = %d AND active = TRUE
                """.formatted(managerId, employeeId)));

        assertThrows(
                SQLException.class,
                () -> insertScope(otherManagerId, employeeId, adminId),
                "An employee may have only one active manager"
        );
        assertThrows(
                SQLException.class,
                () -> insertScope(managerId, managerId, adminId),
                "Self-management must be rejected"
        );
    }

    @Test
    void databaseRejectsInvalidRolesAndEndsScopeWhenAccountIsDeactivated() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        long adminId = insertUser("scope-admin-2@example.test", "ADMIN");
        long managerId = insertUser("scope-manager-3@example.test", "MANAGER");
        long employeeId = insertUser("scope-employee-2@example.test", "EMPLOYEE");

        assertThrows(
                SQLException.class,
                () -> insertScope(employeeId, managerId, adminId),
                "Role constraints must be enforced below the service layer"
        );

        insertScope(managerId, employeeId, adminId);
        try (Connection connection = connection();
             PreparedStatement update =
                     connection.prepareStatement("UPDATE users SET active = FALSE WHERE id = ?")) {
            update.setLong(1, managerId);
            update.executeUpdate();
        }
        assertEquals(0, scalarInt("""
                SELECT COUNT(*) FROM manager_employee_scopes
                WHERE manager_user_id = %d AND active = TRUE
                """.formatted(managerId)));
        assertEquals(1, scalarInt("""
                SELECT COUNT(*) FROM manager_employee_scopes
                WHERE manager_user_id = %d AND active = FALSE AND ended_at IS NOT NULL
                """.formatted(managerId)));
    }

    private long insertUser(String email, String role) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (full_name, email, password, role, active)
                     VALUES (?, ?, ?, ?, TRUE)
                     RETURNING id
                     """)) {
            statement.setString(1, role + " User");
            statement.setString(2, email);
            statement.setString(3, "{test-only-disabled-password}");
            statement.setString(4, role);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private void insertScope(long managerId, long employeeId, long adminId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO manager_employee_scopes (
                         manager_user_id, employee_user_id, assigned_by_user_id
                     ) VALUES (?, ?, ?)
                     """)) {
            statement.setLong(1, managerId);
            statement.setLong(2, employeeId);
            statement.setLong(3, adminId);
            statement.executeUpdate();
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    private String jdbcUrl() {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            return externalUrl;
        }
        ensureContainerStarted();
        return postgres.getJdbcUrl();
    }

    private String username() {
        if (System.getProperty("leaflet.test.database.url") != null) {
            return System.getProperty("leaflet.test.database.username", "postgres");
        }
        ensureContainerStarted();
        return postgres.getUsername();
    }

    private String password() {
        if (System.getProperty("leaflet.test.database.url") != null) {
            return System.getProperty("leaflet.test.database.password", "");
        }
        ensureContainerStarted();
        return postgres.getPassword();
    }

    private void ensureContainerStarted() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
        }
    }
}
