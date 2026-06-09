package com.ems.backend.common;

import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectRepository;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {
    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectAccessService projectAccessService;

    private User admin;
    private User manager;
    private User employee;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = user(1L, Role.ADMIN);
        manager = user(2L, Role.MANAGER);
        employee = user(3L, Role.EMPLOYEE);
        outsider = user(4L, Role.EMPLOYEE);

        project = new Project();
        project.setId(10L);
        project.setManager(manager);
        project.setAssignedEmployees(Set.of(employee));
    }

    @Test
    void adminCanAccessAnyProject() {
        assertTrue(projectAccessService.canAccessProject(admin, project));
    }

    @Test
    void managerCanAccessManagedProject() {
        assertTrue(projectAccessService.canAccessProject(manager, project));
    }

    @Test
    void managerCanManageManagedProject() {
        assertTrue(projectAccessService.canManageProject(manager, project));
    }

    @Test
    void employeeCannotManageAssignedProject() {
        assertFalse(projectAccessService.canManageProject(employee, project));
    }

    @Test
    void assignedEmployeeCanAccessProject() {
        assertTrue(projectAccessService.canAccessProject(employee, project));
    }

    @Test
    void unassignedEmployeeCannotAccessProject() {
        assertFalse(projectAccessService.canAccessProject(outsider, project));
    }

    @Test
    void requireAccessibleProjectThrowsForUnauthorizedUser() {
        when(projectRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(project));

        assertThrows(ResponseStatusException.class, () -> projectAccessService.requireAccessibleProject(10L, outsider));
    }

    @Test
    void requireManageableProjectThrowsForAssignedEmployee() {
        when(projectRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(project));

        assertThrows(ResponseStatusException.class, () -> projectAccessService.requireManageableProject(10L, employee));
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setEmail(id + "@example.com");
        user.setFullName("User " + id);
        user.setActive(true);
        return user;
    }
}
