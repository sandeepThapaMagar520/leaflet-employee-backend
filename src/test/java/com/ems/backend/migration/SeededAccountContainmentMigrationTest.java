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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("databaseAvailable")
class SeededAccountContainmentMigrationTest {
    private static final String SEED_PASSWORD_HASH =
            "$2b$12$BxGLzWtn2LXsSOI0AQwb9eC/lAlxvJxSFf2nm3SkTkoxDxkXfnxy2";

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
    void migrationDisablesOnlyKnownAccountsAndValidatesFlywayHistory() throws Exception {
        Flyway throughV32 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target("32")
                .cleanDisabled(false)
                .load();
        throughV32.clean();
        throughV32.migrate();

        try (Connection connection = connection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO users (
                         full_name, email, password, role, active, password_reset_token
                     ) VALUES (?, ?, ?, ?, TRUE, ?)
                     """)) {
            insert.setString(1, "Real Production User");
            insert.setString(2, "real.user@example.net");
            insert.setString(3, SEED_PASSWORD_HASH);
            insert.setString(4, "EMPLOYEE");
            insert.setString(5, "real-user-reset-token");
            insert.executeUpdate();
        }

        Flyway throughV33 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target("33")
                .load();
        throughV33.migrate();

        assertEquals(5, scalarInt("""
                SELECT COUNT(*) FROM users
                WHERE email IN (
                    'admin@example.com',
                    'superadmin@ems.com',
                    'employee@example.com',
                    'sandeep@gmail.com',
                    'sam@gmail.com'
                )
                  AND active = FALSE
                  AND password = '{DISABLED_BY_V33_SECURITY_CONTAINMENT}'
                  AND password_otp IS NULL
                  AND password_reset_token IS NULL
                  AND email_change_otp IS NULL
                  AND must_change_password = TRUE
                """));
        assertEquals(5, scalarInt("""
                SELECT COUNT(*) FROM staff_audit_events
                WHERE description = 'Account disabled by V33 security containment because it retained a published seed credential.'
                """));

        try (Connection connection = connection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT active, password, password_reset_token
                     FROM users
                     WHERE email = 'real.user@example.net'
                     """);
             ResultSet result = query.executeQuery()) {
            assertTrue(result.next());
            assertTrue(result.getBoolean("active"));
            assertEquals(SEED_PASSWORD_HASH, result.getString("password"));
            assertEquals("real-user-reset-token", result.getString("password_reset_token"));
        }

        Flyway throughV34 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target("34")
                .load();
        throughV34.migrate();

        assertEquals(0, scalarInt("""
                SELECT COUNT(*) FROM users
                WHERE password_reset_token_hash IS NOT NULL
                   OR password_otp_hash IS NOT NULL
                   OR email_verification_token_hash IS NOT NULL
                   OR email_change_otp_hash IS NOT NULL
                """));
        assertTrue(throughV34.validateWithResult().validationSuccessful);
        assertEquals("34", throughV34.info().current().getVersion().getVersion());
    }

    private Connection connection() throws Exception {
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
        String externalUrl = System.getProperty("leaflet.test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            return System.getProperty("leaflet.test.database.username", "postgres");
        }
        ensureContainerStarted();
        return postgres.getUsername();
    }

    private String password() {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
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

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
