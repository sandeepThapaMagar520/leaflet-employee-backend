package com.ems.backend.authorization;

import com.ems.backend.attendance.AttendanceCorrectionRequest;
import com.ems.backend.leave.LeaveRequest;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationPolicyServiceTest {
    @Mock
    private ManagerEmployeeScopeRepository scopeRepository;
    @Mock
    private SecurityAuditService auditService;

    private AuthorizationPolicyService policy;
    private User admin;
    private User manager;
    private User employee;
    private User otherEmployee;

    @BeforeEach
    void setUp() {
        policy = new AuthorizationPolicyService(scopeRepository, auditService);
        admin = user(1L, Role.ADMIN);
        manager = user(2L, Role.MANAGER);
        employee = user(3L, Role.EMPLOYEE);
        otherEmployee = user(4L, Role.EMPLOYEE);
    }

    @Test
    void managerCanViewOnlyAnActivelyScopedEmployee() {
        when(scopeRepository.existsByManagerIdAndEmployeeIdAndActiveTrue(2L, 3L))
                .thenReturn(true);
        when(scopeRepository.existsByManagerIdAndEmployeeIdAndActiveTrue(2L, 4L))
                .thenReturn(false);

        assertTrue(policy.canViewEmployee(manager, employee));
        assertFalse(policy.canViewEmployee(manager, otherEmployee));
    }

    @Test
    void managerCannotViewScopedEmployeesPrivateProfileOrHrDocuments() {
        when(scopeRepository.existsByManagerIdAndEmployeeIdAndActiveTrue(2L, 3L))
                .thenReturn(true);

        assertTrue(policy.canViewEmployeeDirectoryEntry(manager, employee));
        assertFalse(policy.canViewEmployeePrivateProfile(manager, employee));
        assertFalse(policy.canEditEmployeeRecord(manager, employee));
    }

    @Test
    void reviewerCannotApproveOwnLeaveOrCorrection() {
        LeaveRequest leave = new LeaveRequest();
        leave.setId(10L);
        leave.setUser(manager);
        AttendanceCorrectionRequest correction = new AttendanceCorrectionRequest();
        correction.setId(20L);
        correction.setUser(manager);

        assertFalse(policy.canReviewLeave(manager, leave));
        assertFalse(policy.canReviewAttendanceCorrection(manager, correction));
        assertThrows(
                ResponseStatusException.class,
                () -> policy.requireReviewLeave(manager, leave)
        );
        verify(auditService).recordBestEffort(
                manager.getId(),
                manager.getId(),
                "AUTHORIZATION_DENIED",
                "DENIED",
                "SELF_LEAVE_REVIEW",
                manager.getEmail(),
                null
        );
    }

    @Test
    void administratorCanReviewEmployeeRequestButNotTheirOwn() {
        LeaveRequest employeeLeave = new LeaveRequest();
        employeeLeave.setUser(employee);
        LeaveRequest ownLeave = new LeaveRequest();
        ownLeave.setUser(admin);

        assertTrue(policy.canReviewLeave(admin, employeeLeave));
        assertFalse(policy.canReviewLeave(admin, ownLeave));
    }

    @Test
    void managerCannotOverrideOwnAttendance() {
        assertFalse(policy.canManageAttendance(manager, manager));
        assertThrows(
                ResponseStatusException.class,
                () -> policy.requireManageAttendance(manager, manager)
        );
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setEmail("user" + id + "@example.com");
        user.setFullName("User " + id);
        user.setActive(true);
        return user;
    }
}
