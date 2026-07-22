package com.ems.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("databaseAvailable")
class SecureMediaMigrationTest {
    private static PostgreSQLContainer<?> postgres;

    static boolean databaseAvailable() {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        return (externalUrl != null && !externalUrl.isBlank())
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void v36PreservesRecordsAndDoesNotFabricateVerifiedMedia() throws Exception {
        Flyway throughV35 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("35"))
                .cleanDisabled(false)
                .load();
        throughV35.clean();
        throughV35.migrate();

        long userId;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (
                         full_name, email, password, role, active, profile_photo_url
                     ) VALUES ('Legacy Employee', 'legacy-media@example.test',
                         '{disabled}', 'EMPLOYEE', TRUE, 'https://legacy.example/photo.jpg')
                     RETURNING id
                     """);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            userId = result.getLong(1);
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO staff_documents (
                         user_id, document_type, file_name, file_url
                     ) VALUES (?, 'CONTRACT', 'legacy-contract.pdf',
                         'https://legacy.example/contract.pdf')
                     """)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }

        Flyway latest = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();

        assertEquals("41", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM media_assets WHERE status IN ('VERIFIED', 'ATTACHED')
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM users
                WHERE id = %d
                  AND profile_media_asset_id IS NULL
                  AND profile_photo_legacy_status = 'LEGACY_UNVERIFIED'
                """.formatted(userId)));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM staff_documents
                WHERE user_id = %d
                  AND media_asset_id IS NULL
                  AND legacy_asset_status = 'LEGACY_PRIVATE_REVIEW_REQUIRED'
                """.formatted(userId)));
        assertTrue(scalar("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE indexname IN (
                    'uq_media_provider_asset',
                    'uq_media_provider_public_identity',
                    'idx_media_unattached_cleanup'
                )
                """) >= 3);
    }

    @Test
    void v41AddsStructuralStatusWithoutReleasingQuarantinedAssets() throws Exception {
        Flyway throughV40 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("40"))
                .cleanDisabled(false)
                .load();
        throughV40.clean();
        throughV40.migrate();

        long userId;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (full_name, email, password, role, active)
                     VALUES ('Media Owner', 'media-owner@example.test', '{disabled}',
                         'EMPLOYEE', TRUE)
                     RETURNING id
                     """);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            userId = result.getLong(1);
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO media_assets (
                         id, owner_user_id, created_by_user_id, purpose, status,
                         provider, resource_type, delivery_type, detected_mime_type,
                         detected_format, size_bytes, checksum_sha256, private_asset,
                         scanning_status
                     ) VALUES (
                         '11111111-1111-1111-1111-111111111111', ?, ?,
                         'TASK_ATTACHMENT', 'QUARANTINED', 'CLOUDINARY', 'raw',
                         'authenticated', 'application/pdf', 'pdf', 10,
                         repeat('a', 64), TRUE, 'UNAVAILABLE'
                     )
                     """)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }

        Flyway latest = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();

        assertEquals("41", latest.info().current().getVersion().getVersion());
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM media_assets
                WHERE id = '11111111-1111-1111-1111-111111111111'
                  AND status = 'QUARANTINED'
                  AND scanning_status = 'UNAVAILABLE'
                """));
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE media_assets SET scanning_status = 'STRUCTURE_VALIDATED'
                     WHERE id = '11111111-1111-1111-1111-111111111111'
                     """)) {
            assertEquals(1, statement.executeUpdate());
        }
        assertTrue(latest.validateWithResult().validationSuccessful);
    }

    private int scalar(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    private String jdbcUrl() {
        String external = System.getProperty("leaflet.test.database.url");
        if (external != null && !external.isBlank()) return external;
        ensureContainer();
        return postgres.getJdbcUrl();
    }

    private String username() {
        if (System.getProperty("leaflet.test.database.url") != null) {
            return System.getProperty("leaflet.test.database.username", "postgres");
        }
        ensureContainer();
        return postgres.getUsername();
    }

    private String password() {
        if (System.getProperty("leaflet.test.database.url") != null) {
            return System.getProperty("leaflet.test.database.password", "");
        }
        ensureContainer();
        return postgres.getPassword();
    }

    private void ensureContainer() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
        }
    }
}
