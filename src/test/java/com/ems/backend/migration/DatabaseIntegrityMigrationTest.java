package com.ems.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("databaseAvailable")
class DatabaseIntegrityMigrationTest {
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
    void cleanMigrationCreatesAllPhaseFourGuards() throws Exception {
        Flyway flyway = latestFlyway();
        flyway.clean();
        flyway.migrate();

        assertEquals("40", flyway.info().current().getVersion().getVersion());
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(7, scalar("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE indexname IN (
                    'uq_attendance_sessions_one_active_user',
                    'uq_attendance_corrections_one_pending_session',
                    'uq_users_email_ci',
                    'uq_users_pending_email_ci',
                    'uq_users_employee_id_ci',
                    'uq_project_task_boards_name_ci',
                    'idx_attendance_sessions_user_time_range'
                )
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ex_attendance_sessions_no_overlap'
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ex_leave_requests_no_active_overlap'
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'company_holidays'
                """));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE indexname = 'uq_project_payments_project_idempotency'
                """));
    }

    @Test
    void directSqlCannotBypassIdentityAttendanceOrFinancialIntegrity() throws Exception {
        Flyway flyway = latestFlyway();
        flyway.clean();
        flyway.migrate();

        long userId = insertUser("  Integrity.User@Example.Test  ");
        long otherUserId = insertUser("other-integrity@example.test");
        assertEquals("integrity.user@example.test", scalarString(
                "SELECT email FROM users WHERE id = " + userId
        ));
        assertThrows(SQLException.class, () -> insertUser("integrity.user@example.test"));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE users SET pending_email = 'INTEGRITY.USER@example.test' WHERE id = ?",
                otherUserId
        ));
        execute("UPDATE users SET employee_id = 'EMP-001' WHERE id = ?", userId);
        assertThrows(SQLException.class, () -> execute(
                "UPDATE users SET employee_id = ' emp-001 ' WHERE id = ?",
                otherUserId
        ));

        execute("""
                INSERT INTO attendance_sessions(user_id, start_time)
                VALUES (?, TIMESTAMP '2026-07-20 09:00:00')
                """, userId);
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO attendance_sessions(user_id, start_time)
                VALUES (?, TIMESTAMP '2026-07-20 10:00:00')
                """, userId));

        long adminId = firstUserId();
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO projects(
                    name, status, start_date, due_date, manager_id, created_by_id, budget_amount
                ) VALUES (
                    'Invalid project', 'ACTIVE', DATE '2026-07-21', DATE '2026-07-20', ?, ?, -1
                )
                """, adminId, adminId));
        long projectId = insertProject(adminId);
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO project_payments(project_id, amount, paid_at, created_by_id)
                VALUES (?, 0, CURRENT_TIMESTAMP, ?)
                """, projectId, adminId));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE projects SET status = 'UNKNOWN' WHERE id = ?",
                projectId
        ));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE projects SET version = -1 WHERE id = ?",
                projectId
        ));
    }

    @Test
    void dirtyDuplicateAttendanceBlocksUpgradeWithoutRewritingRows() throws Exception {
        Flyway throughV36 = Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("36"))
                .cleanDisabled(false)
                .load();
        throughV36.clean();
        throughV36.migrate();
        long userId = firstUserId();
        execute("""
                INSERT INTO attendance_sessions(user_id, start_time)
                VALUES (?, TIMESTAMP '2026-07-20 09:00:00'),
                       (?, TIMESTAMP '2026-07-20 10:00:00')
                """, userId, userId);

        Flyway latest = latestFlyway();
        assertThrows(FlywayException.class, latest::migrate);
        assertEquals(2, scalar("SELECT COUNT(*) FROM attendance_sessions WHERE user_id = " + userId));
    }

    @Test
    void concurrentDuplicateSubmissionsHaveExactlyOneWinner() throws Exception {
        Flyway flyway = latestFlyway();
        flyway.clean();
        flyway.migrate();
        long userId = insertUser("concurrent-integrity@example.test");

        int attendanceWinners = runConcurrently(() -> execute("""
                INSERT INTO attendance_sessions(user_id, start_time)
                VALUES (?, TIMESTAMP '2026-07-20 09:00:00')
                """, userId));
        assertEquals(1, attendanceWinners);
        assertEquals(1, scalar("SELECT COUNT(*) FROM attendance_sessions WHERE user_id = " + userId));

        execute("""
                UPDATE attendance_sessions
                SET end_time = TIMESTAMP '2026-07-20 17:00:00', total_hours = 8
                WHERE user_id = ?
                """, userId);
        long sessionId = scalarLong(
                "SELECT id FROM attendance_sessions WHERE user_id = " + userId
        );
        int correctionWinners = runConcurrently(() -> execute("""
                INSERT INTO attendance_correction_requests(
                    attendance_session_id, user_id, original_start_time, original_end_time,
                    requested_start_time, requested_end_time, reason
                ) VALUES (
                    ?, ?, TIMESTAMP '2026-07-20 09:00:00', TIMESTAMP '2026-07-20 17:00:00',
                    TIMESTAMP '2026-07-20 09:15:00', TIMESTAMP '2026-07-20 17:15:00',
                    'Concurrent correction'
                )
                """, sessionId, userId));
        assertEquals(1, correctionWinners);

        int leaveWinners = runConcurrently(() -> execute("""
                INSERT INTO leave_requests(
                    user_id, leave_type, status, start_date, end_date, reason
                ) VALUES (?, 'ANNUAL', 'PENDING', DATE '2026-08-01', DATE '2026-08-05', 'Concurrent leave')
                """, userId));
        assertEquals(1, leaveWinners);
        assertEquals(1, scalar(
                "SELECT COUNT(*) FROM leave_requests WHERE user_id = " + userId
        ));

        long adminId = firstUserId();
        long projectId = insertProject(adminId);
        String idempotencyKey = "8f20ba39-8308-4f40-9f2c-d1138f456142";
        int paymentWinners = runConcurrently(() -> execute("""
                INSERT INTO project_payments(
                    project_id, amount, paid_at, created_by_id, idempotency_key
                ) VALUES (?, 25, CURRENT_TIMESTAMP, ?, ?::uuid)
                """, projectId, adminId, idempotencyKey));
        assertEquals(1, paymentWinners);
    }

    @Test
    void outboxIdentityAndSkipLockedClaimingArePostgresEnforced() throws Exception {
        Flyway flyway = latestFlyway();
        flyway.clean();
        flyway.migrate();
        long userId = insertUser("outbox-integrity@example.test");
        UUID eventId = UUID.randomUUID();
        insertOutbox(UUID.randomUUID(), eventId, userId, 10, "one");
        assertThrows(SQLException.class, () -> insertOutbox(UUID.randomUUID(), eventId, userId, 10, "duplicate"));
        insertOutbox(UUID.randomUUID(), UUID.randomUUID(), userId, 5, "two");

        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            UUID firstId = claimOne(first);
            UUID secondId = claimOne(second);
            assertTrue(firstId != null && secondId != null && !firstId.equals(secondId));
            first.rollback();
            second.rollback();
        }

        execute("""
                INSERT INTO notifications(user_id,type,title,message,read,created_at)
                VALUES (?, 'SYSTEM', 'Legacy', 'Still readable', FALSE, CURRENT_TIMESTAMP)
                """, userId);
        assertEquals(1, scalar("SELECT count(*) FROM notifications WHERE event_id IS NULL"));
    }

    @Test
    void domainAndOutboxRowsRollbackTogether() throws Exception {
        Flyway flyway = latestFlyway();
        flyway.clean();
        flyway.migrate();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            long userId;
            try (PreparedStatement user = connection.prepareStatement("""
                    INSERT INTO users(full_name,email,password,role,active)
                    VALUES ('Transactional User','transactional-outbox@example.test','{disabled}','EMPLOYEE',TRUE)
                    RETURNING id
                    """); ResultSet result = user.executeQuery()) {
                assertTrue(result.next());
                userId = result.getLong(1);
            }
            try (PreparedStatement outbox = connection.prepareStatement("""
                    INSERT INTO outbox_messages(id,event_id,event_type,channel,recipient_user_id,
                        recipient_address_hash,recipient_address_ciphertext,template_key,payload_ciphertext,
                        max_attempts,idempotency_key)
                    VALUES (?,?, 'ACCOUNT_SETUP','EMAIL',?,'hash',decode('01','hex'),
                        'ACCOUNT_SETUP',decode('02','hex'),6,?)
                    """)) {
                outbox.setObject(1, UUID.randomUUID());
                outbox.setObject(2, eventId);
                outbox.setLong(3, userId);
                outbox.setString(4, eventId.toString());
                outbox.executeUpdate();
            }
            connection.rollback();
        }
        assertEquals(0, scalar("SELECT count(*) FROM users WHERE email='transactional-outbox@example.test'"));
        assertEquals(0, scalar("SELECT count(*) FROM outbox_messages WHERE event_id='" + eventId + "'::uuid"));
    }

    private void insertOutbox(UUID id, UUID eventId, long userId, int priority, String suffix) throws SQLException {
        execute("""
                INSERT INTO outbox_messages(id,event_id,event_type,channel,recipient_user_id,
                    recipient_address_hash,recipient_address_ciphertext,template_key,payload_ciphertext,
                    max_attempts,priority,idempotency_key)
                VALUES (?,?,'TEST_EVENT','EMAIL',?,'hash',decode('01','hex'),'IN_APP_NOTIFICATION',
                    decode('02','hex'),6,?,?)
                """, id, eventId, userId, priority, eventId + ":EMAIL:" + suffix);
    }

    private UUID claimOne(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM outbox_messages WHERE status='PENDING' AND available_at <= CURRENT_TIMESTAMP
                ORDER BY priority DESC,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private int runConcurrently(SqlMutation mutation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> attemptAfterBarrier(mutation, ready, start)),
                    executor.submit(() -> attemptAfterBarrier(mutation, ready, start))
            );
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) winners++;
            }
            return winners;
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean attemptAfterBarrier(
            SqlMutation mutation,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            mutation.execute();
            return true;
        } catch (SQLException expectedConflict) {
            return false;
        }
    }

    private Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private long insertUser(String email) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users(full_name, email, password, role, active)
                     VALUES ('Integrity User', ?, '{disabled}', 'EMPLOYEE', TRUE)
                     RETURNING id
                     """)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long insertProject(long userId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO projects(
                         name, status, start_date, due_date, manager_id, created_by_id, budget_amount
                     ) VALUES (
                         'Valid project', 'ACTIVE', DATE '2026-07-20', DATE '2026-07-21', ?, ?, 1
                     )
                     RETURNING id
                     """)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long firstUserId() throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT id FROM users ORDER BY id LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private int scalar(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
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

    @FunctionalInterface
    private interface SqlMutation {
        void execute() throws SQLException;
    }
}
