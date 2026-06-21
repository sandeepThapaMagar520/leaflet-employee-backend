package com.ems.backend.user;

import com.ems.backend.common.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private StaffDocumentRepository staffDocumentRepository;
    @Mock
    private StaffAuditEventRepository staffAuditEventRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void directoryFiltersDoNotChangeCompanyWideSummary() {
        User incompleteInvite = user(1L, "Invite User", Role.EMPLOYEE, false);
        User completeManager = user(2L, "Complete Manager", Role.MANAGER, true);
        completeManager.setEmployeeId("EMP-002");
        completeManager.setJoiningDate(LocalDate.of(2025, 1, 10));
        completeManager.setJobTitle("Engineering Manager");
        completeManager.setPhone("+977 9800000000");
        completeManager.setEmergencyContact("Family / +977 9811111111");
        completeManager.setDepartment("Engineering");
        completeManager.setLocation("Kathmandu");

        when(userRepository.findAll()).thenReturn(List.of(incompleteInvite, completeManager));

        var filtered = userService.getUsersPaged(
                0, 20, "", null, null, AccountStatus.INVITE_SENT, null, null, true
        );
        var summary = userService.getStaffDirectorySummary();

        assertEquals(1, filtered.totalElements());
        assertEquals("Invite User", filtered.content().getFirst().fullName());
        assertEquals(2, summary.totalStaff());
        assertEquals(1, summary.managers());
        assertEquals(1, summary.onboardingPending());
        assertEquals(1, summary.incompleteRecords());
        assertEquals(List.of("Engineering"), summary.departments());
    }

    private User user(Long id, String name, Role role, boolean verified) {
        User user = new User();
        user.setId(id);
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(" ", ".") + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setEmploymentType(EmploymentType.FULL_TIME);
        user.setEmailVerified(verified);
        user.setMustChangePassword(false);
        return user;
    }
}
