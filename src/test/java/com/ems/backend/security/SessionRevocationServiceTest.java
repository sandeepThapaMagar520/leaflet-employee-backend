package com.ems.backend.security;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRevocationServiceTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.20", "test", "correlation");

    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private SecurityAuditService auditService;

    @Test
    void selfRevocationIncrementsOnlyAuthenticatedUser() {
        User user = user(11L, 3);
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        service().revokeOwnSessions(REQUEST);

        assertEquals(4, user.getSecurityVersion());
        verify(auditService).record(
                user.getId(), user.getId(), "SESSIONS_REVOKED", "SUCCESS", "SELF_REVOKE_ALL",
                user.getEmail(), REQUEST
        );
    }

    @Test
    void adminRevocationIncrementsTargetVersionAndRecordsReason() {
        User admin = user(1L, 2);
        User target = user(2L, 9);
        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));

        service().revokeUserSessions(target.getId(), "Lost device", REQUEST);

        assertEquals(10, target.getSecurityVersion());
        verify(auditService).recordWithDetails(
                admin.getId(), target.getId(), "SESSIONS_REVOKED", "SUCCESS",
                "ADMIN_REVOKE_ALL_WITH_REASON", "Lost device", target.getEmail(), REQUEST
        );
    }

    private SessionRevocationService service() {
        return new SessionRevocationService(userRepository, securityUtils, auditService);
    }

    private User user(Long id, int version) {
        User user = new User();
        user.setId(id);
        user.setEmail("user-" + id + "@example.net");
        user.setSecurityVersion(version);
        return user;
    }
}
