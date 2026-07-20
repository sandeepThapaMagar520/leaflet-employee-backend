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

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationTokenServiceTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.16", "test", "correlation");

    @Mock private UserRepository userRepository;
    @Mock private TokenHashingService tokenHashingService;
    @Mock private SecurityAuditService auditService;

    @Test
    void issueStoresOnlyHashAndConsumeIsSingleUse() {
        User user = user();
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(tokenHashingService.newBearerToken()).thenReturn("raw-verification-token");
        when(tokenHashingService.sha256("raw-verification-token")).thenReturn("verification-hash");

        EmailVerificationTokenService.IssuedVerification issued = service().issue(user.getId());

        assertEquals("raw-verification-token", issued.rawToken());
        assertEquals("verification-hash", user.getEmailVerificationTokenHash());
        assertNotEquals(issued.rawToken(), user.getEmailVerificationTokenHash());

        when(userRepository.findByEmailVerificationTokenHashForUpdate("verification-hash"))
                .thenReturn(Optional.of(user), Optional.empty());

        assertEquals(
                EmailVerificationTokenService.VerificationResult.SUCCESS,
                service().consume("raw-verification-token", REQUEST)
        );
        assertEquals(4, user.getSecurityVersion());
        assertNull(user.getEmailVerificationTokenHash());
        assertEquals(
                EmailVerificationTokenService.VerificationResult.INVALID,
                service().consume("raw-verification-token", REQUEST)
        );
    }

    @Test
    void expiredVerificationTokenIsConsumedWithoutVerifyingEmail() {
        User user = user();
        user.setEmailVerificationTokenHash("verification-hash");
        user.setEmailVerificationExpiresAt(Instant.now().minusSeconds(1));
        when(tokenHashingService.sha256("raw-verification-token")).thenReturn("verification-hash");
        when(userRepository.findByEmailVerificationTokenHashForUpdate("verification-hash"))
                .thenReturn(Optional.of(user));

        assertEquals(
                EmailVerificationTokenService.VerificationResult.EXPIRED,
                service().consume("raw-verification-token", REQUEST)
        );
        assertNull(user.getEmailVerificationTokenHash());
        assertEquals(false, user.getEmailVerified());
    }

    private EmailVerificationTokenService service() {
        return new EmailVerificationTokenService(
                userRepository, tokenHashingService, auditService
        );
    }

    private User user() {
        User user = new User();
        user.setId(6L);
        user.setEmail("employee@example.net");
        user.setFullName("Employee");
        user.setActive(true);
        user.setEmailVerified(false);
        user.setSecurityVersion(3);
        return user;
    }
}
