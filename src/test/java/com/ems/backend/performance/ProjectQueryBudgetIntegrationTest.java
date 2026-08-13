package com.ems.backend.performance;

import com.ems.backend.project.ProjectService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@EnabledIf("databaseAvailable")
class ProjectQueryBudgetIntegrationTest {
    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String externalUrl = System.getProperty("leaflet.test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getProperty("leaflet.test.database.username", "postgres"));
            registry.add("spring.datasource.password", () -> System.getProperty("leaflet.test.database.password", ""));
        } else {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("app.mail.enabled", () -> "false");
    }

    static boolean databaseAvailable() {
        String url = System.getProperty("leaflet.test.database.url");
        return (url != null && !url.isBlank())
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) postgres.stop();
    }

    @Autowired private ProjectService projectService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String adminEmail;
    private Statistics statistics;

    @BeforeEach
    void createRepresentativeProjects() {
        Long adminId = jdbc.queryForObject("""
                insert into users(full_name,email,password,role,active,email_verified,must_change_password,
                                  employment_type,timezone,security_version,profile_photo_legacy_status)
                values ('Query Budget Admin','query-budget-admin@example.invalid','x','ADMIN',
                        true,true,false,'FULL_TIME','Asia/Kathmandu',1,'NONE') returning id
                """, Long.class);
        Long assigneeId = jdbc.queryForObject("""
                insert into users(full_name,email,password,role,active,email_verified,must_change_password,
                                  employment_type,timezone,security_version,profile_photo_legacy_status)
                values ('Query Budget Employee','query-budget-employee@example.invalid','x','EMPLOYEE',
                        true,true,false,'FULL_TIME','Asia/Kathmandu',1,'NONE') returning id
                """, Long.class);
        adminEmail = jdbc.queryForObject("select email from users where id=?", String.class, adminId);
        jdbc.update("delete from projects where name like 'Query budget project %'");
        for (int index = 1; index <= 25; index++) {
            Long projectId = jdbc.queryForObject("""
                    insert into projects(name,status,manager_id,created_by_id,budget_amount,
                                         document_legacy_status,version,created_at,updated_at)
                    values (?, 'ACTIVE', ?, ?, 100, 'NONE', 0, current_timestamp, current_timestamp)
                    returning id
                    """, Long.class, "Query budget project " + index, adminId, adminId);
            for (int task = 0; task < 3; task++) {
                jdbc.update("""
                        insert into tasks(title,status,priority,project_id,assigned_to_id,created_by_id,
                                          version,created_at,updated_at)
                        values (?, ?, 'MEDIUM', ?, ?, ?, 0, current_timestamp, current_timestamp)
                        """, "Query budget task " + index + "-" + task,
                        task == 0 ? "DONE" : "TODO", projectId, assigneeId, adminId);
            }
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminEmail, "", List.of()));
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        if (statistics != null) statistics.setStatisticsEnabled(false);
    }

    @Test
    void projectListQueryCountStaysWithinBudgetAsPageSizeGrows() {
        long oneRowQueries = measuredQueries(1);
        long twentyRowQueries = measuredQueries(20);

        // The full page executes Spring Data's count query; the last partial page can omit it.
        assertEquals(8, oneRowQueries, "unexpected full project page query plan");
        assertEquals(7, twentyRowQueries, "unexpected partial project page query plan");
        assertTrue(oneRowQueries <= 8, "project page query budget exceeded: " + oneRowQueries);
        assertTrue(twentyRowQueries <= 8, "project page query budget exceeded: " + twentyRowQueries);
    }

    private long measuredQueries(int size) {
        entityManager.clear();
        statistics.clear();
        projectService.getAllProjectsPaged(0, size, "createdAt", "desc");
        return statistics.getPrepareStatementCount();
    }
}
