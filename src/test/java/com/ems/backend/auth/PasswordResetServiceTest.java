package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.security.TokenHashingService;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.15", "test", "correlation");

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenHashingService tokenHashingService;
    @Mock private SecurityAuditService auditService;

    @Test
    void successfulResetConsumesHashAndIncrementsSecurityVersion() {
        User user = resetUser();
        when(tokenHashingService.sha256("raw-token")).thenReturn("stored-token-hash");
        when(userRepository.findByPasswordResetTokenHashForUpdate("stored-token-hash"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("new-password-hash");

        var result = service().consume("raw-token", "new-password", REQUEST);

        assertEquals(PasswordResetService.ResetResult.SUCCESS, result);
        assertEquals("new-password-hash", user.getPassword());
        assertEquals(8, user.getSecurityVersion());
        assertNull(user.getPasswordResetTokenHash());
        verify(auditService).record(
                null, user.getId(), "PASSWORD_RESET_COMPLETED", "SUCCESS", "TOKEN_CONSUMED",
                user.getEmail(), REQUEST
        );
    }

    @Test
    void consumedOrUnknownTokenFails() {
        when(tokenHashingService.sha256("raw-token")).thenReturn("token-hash");
        when(userRepository.findByPasswordResetTokenHashForUpdate("token-hash"))
                .thenReturn(Optional.empty());

        assertEquals(
                PasswordResetService.ResetResult.INVALID,
                service().consume("raw-token", "new-password", REQUEST)
        );
    }

    @Test
    void expiredTokenIsCleared() {
        User user = resetUser();
        user.setPasswordResetExpiresAt(Instant.now().minusSeconds(1));
        when(tokenHashingService.sha256("raw-token")).thenReturn("stored-token-hash");
        when(userRepository.findByPasswordResetTokenHashForUpdate("stored-token-hash"))
                .thenReturn(Optional.of(user));

        assertEquals(
                PasswordResetService.ResetResult.EXPIRED,
                service().consume("raw-token", "new-password", REQUEST)
        );
        assertNull(user.getPasswordResetTokenHash());
    }

    private PasswordResetService service() {
        return new PasswordResetService(
                userRepository, passwordEncoder, tokenHashingService, auditService
        );
    }

    private User resetUser() {
        User user = new User();
        user.setId(5L);
        user.setEmail("employee@example.net");
        user.setActive(true);
        user.setPassword("old-hash");
        user.setPasswordResetTokenHash("stored-token-hash");
        user.setPasswordResetExpiresAt(Instant.now().plusSeconds(300));
        user.setSecurityVersion(7);
        return user;
    }
}
