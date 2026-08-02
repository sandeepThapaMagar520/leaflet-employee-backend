package com.ems.backend.user;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.media.MediaAttachmentService;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.dto.UpdateUserRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

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
    @Mock
    private SecurityAuditService securityAuditService;
    @Mock
    private AuthorizationPolicyService authorizationPolicyService;
    @Mock
    private MediaAttachmentService mediaAttachmentService;

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

        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(incompleteInvite)));
        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of(incompleteInvite, completeManager));

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

    @Test
    void roleChangeRevokesExistingTokensAndAuditsAccessChange() {
        User target = user(7L, "Target User", Role.EMPLOYEE, true);
        target.setSecurityVersion(4);
        User admin = user(1L, "Admin User", Role.ADMIN, true);
        when(userRepository.findById(target.getId())).thenReturn(java.util.Optional.of(target));
        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(userRepository.save(target)).thenReturn(target);

        userService.updateUser(target.getId(), new UpdateUserRequest(
                target.getFullName(), target.getEmail(), Role.MANAGER, true,
                "Manager", null, null, EmploymentType.FULL_TIME,
                null, null, null, null, "Asia/Kathmandu"
        ));

        assertEquals(5, target.getSecurityVersion());
        verify(securityAuditService).record(
                admin.getId(), target.getId(), "ACCOUNT_ACCESS_CHANGED", "SUCCESS",
                "ROLE_CHANGED", target.getEmail(), null
        );
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
