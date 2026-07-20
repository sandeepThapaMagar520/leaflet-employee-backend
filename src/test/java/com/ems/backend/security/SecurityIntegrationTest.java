package com.ems.backend.security;

import com.ems.backend.auth.OtpChallengeService;
import com.ems.backend.auth.OtpPurpose;
import com.ems.backend.auth.PasswordResetService;
import com.ems.backend.user.EmploymentType;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("databaseAvailable")
class SecurityIntegrationTest {
    private static final String SECRET = "T8v!K2q#W9s@M4x%R7p&N1c*D6h-Z3j+F5y";
    private static final String ISSUER = "leaflet-security-test";
    private static final String AUDIENCE = "leaflet-web-test";

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
        registry.add("app.security.jwt.issuer", () -> ISSUER);
        registry.add("app.security.jwt.audience", () -> AUDIENCE);
        registry.add("app.security.jwt.key-id", () -> "security-test-key");
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
    @Autowired private TokenHashingService tokenHashingService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private OtpChallengeService otpChallengeService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private SecurityAuditService securityAuditService;

    private User employee;

    @BeforeEach
    void createEmployee() {
        employee = new User();
        employee.setFullName("Security Test Employee");
        employee.setEmail("security-" + UUID.randomUUID() + "@example.net");
        employee.setPassword("{noop}not-used");
        employee.setRole(Role.EMPLOYEE);
        employee.setActive(true);
        employee.setEmploymentType(EmploymentType.FULL_TIME);
        employee.setTimezone("Asia/Kathmandu");
        employee.setEmailVerified(true);
        employee.setMustChangePassword(false);
        employee.setSecurityVersion(1);
        employee = userRepository.save(employee);
    }

    @Test
    void publicAndProtectedEndpointsUseCorrect401And403Behavior() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType("application/json")
                        .content("{\"email\":\"unknown@example.net\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/users/summary")
                        .header("Authorization", "Bearer " + jwtService.generateToken(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_INVALID"));
    }

    @Test
    void databaseBackedOtpCooldownIsEnforcedWithoutAccountDisclosure() throws Exception {
        String unknownEmail = "unknown-" + UUID.randomUUID() + "@example.net";
        String requestBody = "{\"email\":\"" + unknownEmail + "\"}";

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .header("X-Forwarded-For", "203.0.113.61")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .header("X-Forwarded-For", "203.0.113.61")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void revokedAndDisabledTokensFailWhileDatabaseRoleChangesTakeEffect() throws Exception {
        String employeeToken = jwtService.generateToken(employee);
        employee.setRole(Role.ADMIN);
        employee = userRepository.saveAndFlush(employee);

        mockMvc.perform(get("/api/v1/users/summary")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());

        employee.setSecurityVersion(2);
        employee = userRepository.saveAndFlush(employee);
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isUnauthorized());

        String replacement = jwtService.generateToken(employee);
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + replacement))
                .andExpect(status().isOk());

        employee.setActive(false);
        userRepository.saveAndFlush(employee);
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + replacement))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredWrongIssuerAndMissingVersionTokensFail() throws Exception {
        for (String token : List.of(
                customToken(1, ISSUER, AUDIENCE, Instant.now().minusSeconds(1)),
                customToken(1, "wrong-issuer", AUDIENCE, Instant.now().plusSeconds(300)),
                customToken(null, ISSUER, AUDIENCE, Instant.now().plusSeconds(300))
        )) {
            mockMvc.perform(get("/api/v1/users/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void concurrentResetConsumptionAllowsExactlyOneSuccess() throws Exception {
        String rawToken = tokenHashingService.newBearerToken();
        employee.setPasswordResetTokenHash(tokenHashingService.sha256(rawToken));
        employee.setPasswordResetExpiresAt(Instant.now().plusSeconds(300));
        employee = userRepository.saveAndFlush(employee);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return passwordResetService.consume(rawToken, "new-password-1", metadata());
            });
            var second = executor.submit(() -> {
                start.await();
                return passwordResetService.consume(rawToken, "new-password-2", metadata());
            });
            start.countDown();
            List<PasswordResetService.ResetResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(value -> value == PasswordResetService.ResetResult.SUCCESS).count());
            assertEquals(1, results.stream().filter(value -> value == PasswordResetService.ResetResult.INVALID).count());
        }
    }

    @Test
    void concurrentOtpVerificationAllowsExactlyOneSuccess() throws Exception {
        OtpChallengeService.IssuedOtp issued =
                otpChallengeService.issue(employee.getId(), OtpPurpose.PASSWORD_RECOVERY, metadata());
        assertTrue(issued.created());

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return otpChallengeService.verify(
                        employee.getEmail(), issued.rawOtp(), OtpPurpose.PASSWORD_RECOVERY, metadata()
                );
            });
            var second = executor.submit(() -> {
                start.await();
                return otpChallengeService.verify(
                        employee.getEmail(), issued.rawOtp(), OtpPurpose.PASSWORD_RECOVERY, metadata()
                );
            });
            start.countDown();
            List<OtpChallengeService.VerificationResult> results = List.of(first.get(), second.get());
            assertEquals(
                    1,
                    results.stream()
                            .filter(value -> value.status() == OtpChallengeService.Status.SUCCESS)
                            .count()
            );
        }
    }

    @Test
    void passwordResetRollsBackWhenRequiredAuditPersistenceFails() {
        String rawToken = tokenHashingService.newBearerToken();
        String tokenHash = tokenHashingService.sha256(rawToken);
        String originalPassword = employee.getPassword();
        int originalVersion = employee.getSecurityVersion();
        employee.setPasswordResetTokenHash(tokenHash);
        employee.setPasswordResetExpiresAt(Instant.now().plusSeconds(300));
        employee = userRepository.saveAndFlush(employee);

        doThrow(new IllegalStateException("simulated required database failure"))
                .when(securityAuditService)
                .record(
                        nullable(Long.class),
                        eq(employee.getId()),
                        eq("PASSWORD_RESET_COMPLETED"),
                        eq("SUCCESS"),
                        eq("TOKEN_CONSUMED"),
                        eq(employee.getEmail()),
                        any(RequestMetadata.class)
                );

        assertThrows(
                IllegalStateException.class,
                () -> passwordResetService.consume(rawToken, "rollback-password", metadata())
        );

        User reloaded = userRepository.findById(employee.getId()).orElseThrow();
        assertEquals(originalPassword, reloaded.getPassword());
        assertEquals(originalVersion, reloaded.getSecurityVersion());
        assertEquals(tokenHash, reloaded.getPasswordResetTokenHash());
    }

    @Test
    void administratorCanRevokeAnotherUsersSessions() throws Exception {
        User admin = new User();
        admin.setFullName("Security Test Admin");
        admin.setEmail("admin-" + UUID.randomUUID() + "@example.net");
        admin.setPassword(passwordEncoder.encode("not-used"));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        admin.setEmploymentType(EmploymentType.FULL_TIME);
        admin.setTimezone("Asia/Kathmandu");
        admin.setEmailVerified(true);
        admin.setMustChangePassword(false);
        admin.setSecurityVersion(1);
        admin = userRepository.saveAndFlush(admin);

        mockMvc.perform(post("/api/v1/users/{id}/sessions/revoke-all", employee.getId())
                        .header("Authorization", "Bearer " + jwtService.generateToken(admin))
                        .contentType("application/json")
                        .content("{\"reason\":\"suspected account compromise\"}"))
                .andExpect(status().isOk());

        assertEquals(
                2,
                userRepository.findById(employee.getId()).orElseThrow().getSecurityVersion()
        );
    }

    @Test
    void concurrentLockedVersionIncrementsAreNotLost() throws Exception {
        Long userId = employee.getId();
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> incrementInsideTransaction(transaction, userId, start));
            var second = executor.submit(() -> incrementInsideTransaction(transaction, userId, start));
            start.countDown();
            first.get();
            second.get();
        }
        assertEquals(3, userRepository.findById(userId).orElseThrow().getSecurityVersion());
    }

    private Void incrementInsideTransaction(
            TransactionTemplate transaction,
            Long userId,
            CountDownLatch start
    ) throws Exception {
        start.await();
        transaction.executeWithoutResult(status -> {
            User locked = userRepository.findByIdForUpdate(userId).orElseThrow();
            locked.setSecurityVersion(locked.getSecurityVersion() + 1);
            userRepository.save(locked);
        });
        return null;
    }

    private String customToken(
            Object version,
            String issuer,
            String audience,
            Instant expiration
    ) {
        var builder = Jwts.builder()
                .subject(employee.getEmail())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(expiration));
        if (version != null) {
            builder.claim("sv", version);
        }
        return builder.signWith(
                Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))
        ).compact();
    }

    private RequestMetadata metadata() {
        return new RequestMetadata("203.0.113.44", "integration-test", UUID.randomUUID().toString());
    }
}
