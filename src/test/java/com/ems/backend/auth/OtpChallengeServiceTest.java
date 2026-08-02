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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpChallengeServiceTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.12", "test", "correlation");

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenHashingService tokenHashingService;
    @Mock private SecurityAuditService auditService;

    @Test
    void correctOtpProducesHashedSingleUseResetToken() {
        User user = challengeUser();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("123456", "otp-hash")).thenReturn(true);
        when(tokenHashingService.newBearerToken()).thenReturn("raw-reset-token");
        when(tokenHashingService.sha256("raw-reset-token")).thenReturn("reset-token-hash");

        var result = service().verify(
                user.getEmail(), "123456", OtpPurpose.PASSWORD_RECOVERY, REQUEST
        );

        assertEquals(OtpChallengeService.Status.SUCCESS, result.status());
        assertEquals("raw-reset-token", result.rawResetToken());
        assertEquals("reset-token-hash", user.getPasswordResetTokenHash());
        assertNotEquals(result.rawResetToken(), user.getPasswordResetTokenHash());
        assertNull(user.getPasswordOtpHash());
    }

    @Test
    void wrongOtpIncrementsAttemptsAndLocksAtMaximum() {
        User user = challengeUser();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        OtpChallengeService.VerificationResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            result = service().verify(
                    user.getEmail(), "000000", OtpPurpose.PASSWORD_RECOVERY, REQUEST
            );
        }

        assertEquals(OtpChallengeService.Status.LOCKED, result.status());
        assertEquals(5, user.getPasswordOtpFailedAttempts());
        assertNull(user.getPasswordOtpHash());

        var retry = service().verify(
                user.getEmail(), "000000", OtpPurpose.PASSWORD_RECOVERY, REQUEST
        );
        assertEquals(OtpChallengeService.Status.EXPIRED, retry.status());
        assertNull(user.getPasswordOtpHash());
    }

    @Test
    void expiredOtpCannotSucceed() {
        User user = challengeUser();
        user.setPasswordOtpExpiresAt(Instant.now().minusSeconds(1));
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));

        var result = service().verify(
                user.getEmail(), "123456", OtpPurpose.PASSWORD_RECOVERY, REQUEST
        );

        assertEquals(OtpChallengeService.Status.EXPIRED, result.status());
        assertNull(user.getPasswordOtpHash());
    }

    @Test
    void issuingOtpReplacesPreviousChallengeAndClearsResetToken() {
        User user = challengeUser();
        user.setPasswordResetTokenHash("old-reset-hash");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("new-otp-hash");

        var issued = service().issue(user.getId(), OtpPurpose.PASSWORD_RECOVERY, REQUEST);

        assertEquals(6, issued.rawOtp().length());
        assertEquals("new-otp-hash", user.getPasswordOtpHash());
        assertNull(user.getPasswordResetTokenHash());
        assertEquals(0, user.getPasswordOtpFailedAttempts());
    }

    private OtpChallengeService service() {
        return new OtpChallengeService(
                userRepository,
                passwordEncoder,
                tokenHashingService,
                properties(),
                auditService
        );
    }

    private OtpSecurityProperties properties() {
        return new OtpSecurityProperties(600, 600, 60, 5, 10, 30, 100, 100, 20, 60);
    }

    private User challengeUser() {
        User user = new User();
        user.setId(3L);
        user.setEmail("employee@example.net");
        user.setFullName("Employee");
        user.setActive(true);
        user.setMustChangePassword(false);
        user.setPasswordOtpHash("otp-hash");
        user.setPasswordOtpPurpose(OtpPurpose.PASSWORD_RECOVERY.name());
        user.setPasswordOtpExpiresAt(Instant.now().plusSeconds(300));
        user.setPasswordOtpFailedAttempts(0);
        return user;
    }
}
