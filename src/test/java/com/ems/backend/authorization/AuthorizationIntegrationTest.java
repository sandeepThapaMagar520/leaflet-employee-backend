package com.ems.backend.authorization;

import com.ems.backend.attendance.AttendanceCorrectionRepository;
import com.ems.backend.attendance.AttendanceCorrectionRequest;
import com.ems.backend.attendance.AttendanceCorrectionStatus;
import com.ems.backend.attendance.AttendanceSession;
import com.ems.backend.attendance.AttendanceSessionRepository;
import com.ems.backend.security.JwtService;
import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectPayment;
import com.ems.backend.task.Task;
import com.ems.backend.user.EmploymentType;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.UUID;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("databaseAvailable")
class AuthorizationIntegrationTest {
    private static final String SECRET = "T8v!K2q#W9s@M4x%R7p&N1c*D6h-Z3j+F5y";
    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add(
                    "spring.datasource.username",
                    () -> System.getProperty("leaflet.test.database.username", "postgres")
            );
            registry.add(
                    "spring.datasource.password",
                    () -> System.getProperty("leaflet.test.database.password", "")
            );
        } else {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("app.security.jwt.secret", () -> SECRET);
        registry.add("app.security.jwt.issuer", () -> "leaflet-authorization-test");
        registry.add("app.security.jwt.audience", () -> "leaflet-web-test");
        registry.add("app.security.jwt.key-id", () -> "authorization-test-key");
        registry.add("app.security.jwt.expiration-ms", () -> "900000");
        registry.add("app.mail.enabled", () -> "false");
    }

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

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AttendanceSessionRepository attendanceSessionRepository;
    @Autowired private AttendanceCorrectionRepository attendanceCorrectionRepository;
    @Autowired private ManagerEmployeeScopeRepository managerScopeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private User admin;
    private User manager;
    private User secondManager;
    private User employee;
    private User otherEmployee;

    @BeforeEach
    void setUp() {
        admin = saveUser(Role.ADMIN, "Admin");
        manager = saveUser(Role.MANAGER, "Manager One");
        secondManager = saveUser(Role.MANAGER, "Manager Two");
        employee = saveUser(Role.EMPLOYEE, "Scoped Employee");
        otherEmployee = saveUser(Role.EMPLOYEE, "Other Employee");
    }

    @Test
    void onlyAdministratorCanAssignScopeAndManagerDirectoryOmitsPrivateFields() throws Exception {
        mockMvc.perform(put("/api/v1/manager-scopes/employees/{employeeId}", employee.getId())
                        .header("Authorization", bearer(manager))
                        .contentType("application/json")
                        .content("{\"managerId\":" + manager.getId() + "}"))
                .andExpect(status().isForbidden());

        assign(admin, employee, manager);

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(manager.getId()))
                .andExpect(jsonPath("$.content[1].id").value(employee.getId()))
                .andExpect(jsonPath("$.content[1].phone").doesNotExist())
                .andExpect(jsonPath("$.content[1].emergencyContact").doesNotExist())
                .andExpect(jsonPath("$.content[1].accountStatus").doesNotExist())
                .andExpect(jsonPath("$.content[1].lastLoginAt").doesNotExist());

        mockMvc.perform(get("/api/v1/users/summary")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossScopeAccessIsDeniedAndReassignmentTakesEffectImmediately() throws Exception {
        assign(admin, employee, manager);

        mockMvc.perform(get("/api/v1/leave-requests/users/{userId}/balance", employee.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/leave-requests/users/{userId}/balance", otherEmployee.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        assign(admin, employee, secondManager);

        mockMvc.perform(get("/api/v1/leave-requests/users/{userId}/balance", employee.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leave-requests/users/{userId}/balance", employee.getId())
                        .header("Authorization", bearer(secondManager)))
                .andExpect(status().isOk());
    }

    @Test
    void managerCannotOverrideOwnOrUnscopedAttendance() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/users/{userId}/active/start", manager.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Authorization test\"}")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(
                                "/api/v1/attendance/users/{userId}/active/start",
                                otherEmployee.getId()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Authorization test\"}")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        assign(admin, employee, manager);
        mockMvc.perform(post("/api/v1/attendance/users/{userId}/active/start", employee.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Authorization test\"}")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(employee.getId()));
    }

    @Test
    void leaveListsAndReviewsEnforceScopeAndReviewerSeparation() throws Exception {
        long employeeLeaveId = createLeave(employee, "Scoped leave");
        long otherLeaveId = createLeave(otherEmployee, "Unscoped leave");
        long managerLeaveId = createLeave(manager, "Manager leave");
        assign(admin, employee, manager);

        mockMvc.perform(get("/api/v1/leave-requests")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id == " + employeeLeaveId + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + managerLeaveId + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + otherLeaveId + ")]").doesNotExist());

        reviewLeave(manager, employeeLeaveId, "approve", 200);
        reviewLeave(manager, otherLeaveId, "approve", 403);
        reviewLeave(manager, managerLeaveId, "reject", 403);
        reviewLeave(employee, otherLeaveId, "approve", 403);
    }

    @Test
    void dailyLogListsMutationsAndExportsUseTheSameScope() throws Exception {
        long employeeLogId = createLog(employee, "Visible scoped work");
        long otherLogId = createLog(otherEmployee, "Hidden unscoped work");
        assign(admin, employee, manager);

        mockMvc.perform(get("/api/v1/logs")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(employeeLogId));

        mockMvc.perform(put("/api/v1/logs/{id}", otherLogId)
                        .header("Authorization", bearer(manager))
                        .contentType("application/json")
                        .content("""
                                {"logDate":"2026-07-18","summary":"Cross-scope edit","problemsFaced":null}
                                """))
                .andExpect(status().isForbidden());

        MvcResult export = mockMvc.perform(get("/api/v1/exports/logs").param("from", "2026-07-01").param("to", "2026-07-31")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        export.getAsyncResult(5_000);
        String exportedCsv = export.getResponse().getContentAsString();
        assertTrue(exportedCsv.contains("Visible scoped work"));
        assertTrue(!exportedCsv.contains("Hidden unscoped work"));
    }

    @Test
    void attendanceCorrectionReviewEnforcesScopeAndSelfReview() throws Exception {
        AttendanceCorrectionRequest scoped = createCorrection(employee);
        AttendanceCorrectionRequest unscoped = createCorrection(otherEmployee);
        AttendanceCorrectionRequest own = createCorrection(manager);
        assign(admin, employee, manager);

        reviewCorrection(manager, scoped.getId(), "approve", 200);
        reviewCorrection(manager, unscoped.getId(), "reject", 403);
        reviewCorrection(manager, own.getId(), "approve", 403);
        reviewCorrection(employee, unscoped.getId(), "approve", 403);
    }

    @Test
    void projectMembershipDoesNotGrantFinancialVisibilityOrMutation() throws Exception {
        JsonNode project = createProject(admin, manager, secondManager, employee);
        long projectId = project.get("id").asLong();

        mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(secondManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManageProject").value(false))
                .andExpect(jsonPath("$.canViewFinancials").value(false))
                .andExpect(jsonPath("$.canRecordPayment").value(false))
                .andExpect(jsonPath("$.budgetAmount").doesNotExist())
                .andExpect(jsonPath("$.totalPaid").doesNotExist())
                .andExpect(jsonPath("$.internalNotes").doesNotExist());

        mockMvc.perform(get("/api/v1/projects/{id}/payments", projectId)
                        .header("Authorization", bearer(secondManager)))
                .andExpect(status().isForbidden());
        recordPayment(secondManager, projectId, 403);
        recordPayment(manager, projectId, 200);

        mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canViewFinancials").value(true))
                .andExpect(jsonPath("$.canRecordPayment").value(true))
                .andExpect(jsonPath("$.budgetAmount").value(10000));
    }

    @Test
    void concurrentReassignmentLeavesExactlyOneActiveRelationship() throws Exception {
        String adminAuthorization = bearer(admin);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return assignStatus(adminAuthorization, employee.getId(), manager.getId());
            });
            var second = executor.submit(() -> {
                start.await();
                return assignStatus(adminAuthorization, employee.getId(), secondManager.getId());
            });
            start.countDown();
            assertEquals(200, first.get());
            assertEquals(200, second.get());
        }

        var active = managerScopeRepository.findByEmployeeIdAndActiveTrue(employee.getId());
        assertTrue(active.isPresent());
        assertTrue(
                active.get().getManager().getId().equals(manager.getId())
                        || active.get().getManager().getId().equals(secondManager.getId())
        );
        assertEquals(
                1,
                managerScopeRepository.countByEmployeeIdAndActiveTrue(employee.getId())
        );
    }

    @Test
    void concurrentEmployeeStartsCreateExactlyOneActiveSession() throws Exception {
        String authorization = bearer(employee);
        List<Integer> statuses = runRace(
                () -> requestStatus(post("/api/v1/attendance/start"), authorization, null),
                () -> requestStatus(post("/api/v1/attendance/start"), authorization, null)
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(
                1,
                attendanceSessionRepository.findByUserIdAndEndTimeIsNull(employee.getId()).size()
        );
    }

    @Test
    void managerOverrideAndEmployeeStartCannotRaceIntoDuplicates() throws Exception {
        assign(admin, employee, manager);
        List<Integer> statuses = runRace(
                () -> requestStatus(
                        post("/api/v1/attendance/start"),
                        bearer(employee),
                        null
                ),
                () -> requestStatus(
                        post("/api/v1/attendance/users/{userId}/active/start", employee.getId()),
                        bearer(manager),
                        "{\"reason\":\"Concurrency test\"}"
                )
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(
                1,
                attendanceSessionRepository.findByUserIdAndEndTimeIsNull(employee.getId()).size()
        );
    }

    @Test
    void concurrentCorrectionDecisionsCreateOneTransitionAuditAndNotification() throws Exception {
        AttendanceCorrectionRequest correction = createCorrection(employee);
        assign(admin, employee, manager);
        List<Integer> statuses = runRace(
                () -> requestStatus(
                        patch("/api/v1/attendance/corrections/{id}/approve", correction.getId()),
                        bearer(manager),
                        "{\"reviewerNote\":\"concurrent approve\"}"
                ),
                () -> requestStatus(
                        patch("/api/v1/attendance/corrections/{id}/reject", correction.getId()),
                        bearer(manager),
                        "{\"reviewerNote\":\"concurrent reject\"}"
                )
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM security_audit_events
                WHERE event_type = 'ATTENDANCE_CORRECTION_REVIEWED'
                  AND details LIKE ?
                """, Integer.class, "%\"correctionId\":" + correction.getId() + "%"));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE link = ?
                """, Integer.class, "/attendance?correction=" + correction.getId()));
    }

    @Test
    void concurrentCorrectionSubmissionsCreateExactlyOnePendingRequest() throws Exception {
        Instant originalStart = Instant.now().minusSeconds(7200);
        Instant originalEnd = Instant.now().minusSeconds(3600);
        AttendanceSession session = new AttendanceSession();
        session.setUser(employee);
        session.setStartTime(originalStart);
        session.setEndTime(originalEnd);
        session.setTotalHours(java.math.BigDecimal.ONE);
        session = attendanceSessionRepository.saveAndFlush(session);
        String body = """
                {
                  "sessionId":%d,
                  "requestedStartTime":"%s",
                  "requestedEndTime":"%s",
                  "reason":"Concurrent correction submission"
                }
                """.formatted(
                session.getId(),
                originalStart.plusSeconds(60),
                originalEnd.plusSeconds(60)
        );

        List<Integer> statuses = runRace(
                () -> requestStatus(
                        post("/api/v1/attendance/corrections"),
                        bearer(employee),
                        body
                ),
                () -> requestStatus(
                        post("/api/v1/attendance/corrections"),
                        bearer(employee),
                        body
                )
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM attendance_correction_requests
                WHERE attendance_session_id = ? AND status = 'PENDING'
                """, Integer.class, session.getId()));
    }

    @Test
    void concurrentLeaveDecisionsCreateOneTransitionAndAudit() throws Exception {
        long leaveId = createLeave(employee, "Concurrent decision");
        assign(admin, employee, manager);
        List<Integer> statuses = runRace(
                () -> requestStatus(
                        patch("/api/v1/leave-requests/{id}/approve", leaveId),
                        bearer(manager),
                        "{\"reviewerNote\":\"concurrent approve\"}"
                ),
                () -> requestStatus(
                        patch("/api/v1/leave-requests/{id}/reject", leaveId),
                        bearer(manager),
                        "{\"reviewerNote\":\"concurrent reject\"}"
                )
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM security_audit_events
                WHERE event_type = 'LEAVE_REVIEWED'
                  AND details = ?
                """, Integer.class, "requestId=" + leaveId));
    }

    @Test
    void concurrentIdenticalLeaveApprovalRetryHasOneWinner() throws Exception {
        long leaveId = createLeave(employee, "Concurrent identical approval");
        assign(admin, employee, manager);
        List<Integer> statuses = runRace(
                () -> requestStatus(
                        patch("/api/v1/leave-requests/{id}/approve", leaveId),
                        bearer(manager),
                        "{\"reviewerNote\":\"same decision\"}"
                ),
                () -> requestStatus(
                        patch("/api/v1/leave-requests/{id}/approve", leaveId),
                        bearer(manager),
                        "{\"reviewerNote\":\"same decision\"}"
                )
        );

        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM security_audit_events
                WHERE event_type = 'LEAVE_REVIEWED' AND details = ?
                """, Integer.class, "requestId=" + leaveId));
    }

    @Test
    void mandatoryAuditFailureRollsBackLeaveApproval() throws Exception {
        long leaveId = createLeave(employee, "Audit rollback");
        assign(admin, employee, manager);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION phase4_reject_leave_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'LEAVE_REVIEWED' THEN
                        RAISE EXCEPTION 'test-only audit failure';
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER phase4_reject_leave_audit_trigger
                BEFORE INSERT ON security_audit_events
                FOR EACH ROW EXECUTE FUNCTION phase4_reject_leave_audit();
        """);
        try {
            assertEquals(500, requestStatus(
                    patch("/api/v1/leave-requests/{id}/approve", leaveId),
                    bearer(manager),
                    "{\"reviewerNote\":\"must rollback\"}"
            ));
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS phase4_reject_leave_audit_trigger
                    ON security_audit_events;
                    DROP FUNCTION IF EXISTS phase4_reject_leave_audit();
                    """);
        }

        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM leave_requests WHERE id = ?",
                String.class,
                leaveId
        ));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM security_audit_events
                WHERE event_type = 'LEAVE_REVIEWED' AND details = ?
                """, Integer.class, "requestId=" + leaveId));
    }

    @Test
    void optimisticVersionsRejectStaleProjectTaskAndPaymentWrites() throws Exception {
        long projectId = createProject(admin, manager, secondManager, employee).get("id").asLong();
        long initialProjectVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                projectId
        );
        long taskId = jdbcTemplate.queryForObject("""
                INSERT INTO tasks(
                    title, status, priority, project_id, assigned_to_id, created_by_id
                ) VALUES ('Versioned task', 'TODO', 'MEDIUM', ?, ?, ?)
                RETURNING id
                """, Long.class, projectId, employee.getId(), admin.getId());
        long paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO project_payments(
                    project_id, amount, paid_at, reference_note, created_by_id
                ) VALUES (?, 10, CURRENT_TIMESTAMP, 'Original', ?)
                RETURNING id
                """, Long.class, projectId, admin.getId());

        assertEquals(1, optimisticRace(
                Project.class,
                projectId,
                (project, marker) -> project.setDescription("Project " + marker)
        ));
        assertEquals(1, optimisticRace(
                Task.class,
                taskId,
                (task, marker) -> task.setDescription("Task " + marker)
        ));
        assertEquals(1, optimisticRace(
                ProjectPayment.class,
                paymentId,
                (payment, marker) -> payment.setReferenceNote("Payment " + marker)
        ));

        updateInNewTransaction(
                Project.class,
                projectId,
                project -> project.setDescription("Successful current-version retry")
        );
        assertEquals(initialProjectVersion + 2, jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                projectId
        ));
    }

    private <T> int optimisticRace(
            Class<T> entityType,
            long entityId,
            BiConsumer<T, String> mutation
    ) throws Exception {
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch mutate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> staleWriter(entityType, entityId, "first", mutation, loaded, mutate)
            );
            var second = executor.submit(
                    () -> staleWriter(entityType, entityId, "second", mutation, loaded, mutate)
            );
            loaded.await();
            mutate.countDown();
            return (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        }
    }

    private <T> boolean staleWriter(
            Class<T> entityType,
            long entityId,
            String marker,
            BiConsumer<T, String> mutation,
            CountDownLatch loaded,
            CountDownLatch mutate
    ) throws InterruptedException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T entity = entityManager.find(entityType, entityId);
            loaded.countDown();
            mutate.await();
            mutation.accept(entity, marker);
            transaction.commit();
            return true;
        } catch (RuntimeException expectedConflict) {
            if (transaction.isActive()) transaction.rollback();
            return false;
        } finally {
            entityManager.close();
        }
    }

    private <T> void updateInNewTransaction(
            Class<T> entityType,
            long entityId,
            java.util.function.Consumer<T> mutation
    ) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            mutation.accept(entityManager.find(entityType, entityId));
            transaction.commit();
        } finally {
            if (transaction.isActive()) transaction.rollback();
            entityManager.close();
        }
    }

    private List<Integer> runRace(RequestCall firstCall, RequestCall secondCall) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return firstCall.execute();
            });
            var second = executor.submit(() -> {
                start.await();
                return secondCall.execute();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private int requestStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String authorization,
            String body
    ) throws Exception {
        builder.header("Authorization", authorization);
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private void assign(User actor, User target, User targetManager) throws Exception {
        mockMvc.perform(put("/api/v1/manager-scopes/employees/{employeeId}", target.getId())
                        .header("Authorization", bearer(actor))
                        .contentType("application/json")
                        .content("{\"managerId\":" + targetManager.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(target.getId()))
                .andExpect(jsonPath("$.managerId").value(targetManager.getId()));
    }

    private int assignStatus(String authorization, long employeeId, long managerId)
            throws Exception {
        return mockMvc.perform(put("/api/v1/manager-scopes/employees/{employeeId}", employeeId)
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"managerId\":" + managerId + "}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private long createLeave(User user, String reason) throws Exception {
        LocalDate leaveDate = LocalDate.now().plusDays(10);
        String response = mockMvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(user))
                        .contentType("application/json")
                        .content("""
                                {
                                  "leaveType":"ANNUAL",
                                  "startDate":"%s",
                                  "endDate":"%s",
                                  "reason":"%s"
                                }
                                """.formatted(leaveDate, leaveDate, reason)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void reviewLeave(User reviewer, long leaveId, String action, int statusCode)
            throws Exception {
        mockMvc.perform(patch("/api/v1/leave-requests/{id}/{action}", leaveId, action)
                        .header("Authorization", bearer(reviewer))
                        .contentType("application/json")
                        .content("{\"reviewerNote\":\"integration decision\"}"))
                .andExpect(status().is(statusCode));
    }

    private long createLog(User user, String summary) throws Exception {
        String response = mockMvc.perform(post("/api/v1/logs")
                        .header("Authorization", bearer(user))
                        .contentType("application/json")
                        .content("""
                                {
                                  "logDate":"2026-07-18",
                                  "summary":"%s",
                                  "problemsFaced":null
                                }
                                """.formatted(summary)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private AttendanceCorrectionRequest createCorrection(User owner) {
        Instant start = Instant.now().minusSeconds(7200);
        Instant end = Instant.now().minusSeconds(3600);
        AttendanceSession session = new AttendanceSession();
        session.setUser(owner);
        session.setStartTime(start);
        session.setEndTime(end);
        session = attendanceSessionRepository.saveAndFlush(session);

        AttendanceCorrectionRequest correction = new AttendanceCorrectionRequest();
        correction.setAttendanceSession(session);
        correction.setUser(owner);
        correction.setOriginalStartTime(start);
        correction.setOriginalEndTime(end);
        correction.setRequestedStartTime(start.plusSeconds(60));
        correction.setRequestedEndTime(end.plusSeconds(60));
        correction.setReason("Integration correction");
        correction.setStatus(AttendanceCorrectionStatus.PENDING);
        return attendanceCorrectionRepository.saveAndFlush(correction);
    }

    private void reviewCorrection(User reviewer, long correctionId, String action, int statusCode)
            throws Exception {
        mockMvc.perform(patch(
                                "/api/v1/attendance/corrections/{id}/{action}",
                                correctionId,
                                action
                        )
                        .header("Authorization", bearer(reviewer))
                        .contentType("application/json")
                        .content("{\"reviewerNote\":\"integration decision\"}"))
                .andExpect(status().is(statusCode));
    }

    private JsonNode createProject(
            User creator,
            User projectManager,
            User memberManager,
            User assignedEmployee
    ) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(creator))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name":"Authorization Project",
                                  "description":"Phase 2 financial boundary",
                                  "startDate":"2026-07-20",
                                  "dueDate":"2026-09-20",
                                  "managerId":%d,
                                  "assignedEmployeeIds":[%d,%d],
                                  "memberPermissions":[],
                                  "clientNotes":"Visible",
                                  "budgetAmount":10000,
                                  "internalNotes":"Financially sensitive"
                                }
                                """.formatted(
                                projectManager.getId(),
                                memberManager.getId(),
                                assignedEmployee.getId()
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void recordPayment(User user, long projectId, int statusCode) throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/payments", projectId)
                        .header("Authorization", bearer(user))
                        .contentType("application/json")
                        .content("""
                                {
                                  "amount":100,
                                  "paidAt":"2026-07-20T04:00:00Z",
                                  "referenceNote":"Authorization test",
                                  "attachments":[]
                                }
                                """))
                .andExpect(status().is(statusCode));
    }

    private User saveUser(Role role, String name) {
        User user = new User();
        user.setFullName(name + " " + UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.net");
        user.setPassword("{noop}not-used");
        user.setRole(role);
        user.setActive(true);
        user.setEmploymentType(EmploymentType.FULL_TIME);
        user.setTimezone("Asia/Kathmandu");
        user.setEmailVerified(true);
        user.setMustChangePassword(false);
        user.setSecurityVersion(1);
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    @FunctionalInterface
    private interface RequestCall {
        int execute() throws Exception;
    }
}
