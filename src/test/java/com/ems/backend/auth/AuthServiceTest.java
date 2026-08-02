package com.ems.backend.auth;

import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.PasswordResetRequest;
import com.ems.backend.auth.dto.SetPasswordRequest;
import com.ems.backend.auth.dto.StartAccountSetupRequest;
import com.ems.backend.auth.dto.VerifyPasswordOtpRequest;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.outbox.OutboxEnqueueRequest;
import com.ems.backend.outbox.OutboxService;
import com.ems.backend.security.JwtService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.StaffAuditEventRepository;
import com.ems.backend.user.User;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    private static final RequestMetadata REQUEST = new RequestMetadata(
            "203.0.113.10", "test-agent", "test-correlation"
    );

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private SecurityUtils securityUtils;
    @Mock private UserProfileService userProfileService;
    @Mock private OutboxService outboxService;
    @Mock private StaffAuditEventRepository staffAuditEventRepository;
    @Mock private OtpRequestGuard otpRequestGuard;
    @Mock private OtpChallengeService otpChallengeService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private SecurityAuditService securityAuditService;
    @Mock private EmailChangeOtpService emailChangeOtpService;

    @Test
    void validLoginIssuesVersionedTokenForDatabaseUser() {
        User user = user();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", user.getPassword())).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("access-token");

        var response = service().login(
                new LoginRequest(user.getEmail(), "correct-password"),
                REQUEST
        );

        assertEquals("access-token", response.accessToken());
        verify(jwtService).generateToken(user);
        verify(securityAuditService).record(
                user.getId(), user.getId(), "LOGIN_SUCCEEDED", "SUCCESS", null, user.getEmail(), REQUEST
        );
    }

    @Test
    void disabledUserReceivesGenericUnauthorizedLoginFailure() {
        User user = user();
        user.setActive(false);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service().login(new LoginRequest(user.getEmail(), "anything"), REQUEST)
        );

        assertEquals(401, exception.getStatusCode().value());
        verify(jwtService, never()).generateToken(any(User.class));
    }

    @Test
    void passwordChangeIncrementsSecurityVersion() {
        User user = user();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        service().changePassword(
                new ChangePasswordRequest("old-password", "new-password"),
                REQUEST
        );

        assertEquals(2, user.getSecurityVersion());
        assertEquals("new-hash", user.getPassword());
        verify(securityAuditService).record(
                user.getId(), user.getId(), "PASSWORD_CHANGED", "SUCCESS",
                "AUTHENTICATED_CHANGE", user.getEmail(), REQUEST
        );
    }

    @Test
    void unknownPasswordResetRequestUsesGenericPathWithoutSendingMail() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        service().requestPasswordReset(
                new PasswordResetRequest("missing@example.com"),
                REQUEST
        );

        verify(otpRequestGuard).checkIssuance("missing@example.com", REQUEST);
        verify(outboxService, never()).enqueue(any());
    }

    @Test
    void accountSetupCreatesOtpThenDeliversOutsideChallengeTransaction() {
        User user = user();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("temporary-password", user.getPassword())).thenReturn(true);
        when(otpChallengeService.issue(user.getId(), OtpPurpose.ACCOUNT_SETUP, REQUEST))
                .thenReturn(new OtpChallengeService.IssuedOtp(
                        user.getId(), user.getEmail(), user.getFullName(), "123456",
                        OtpPurpose.ACCOUNT_SETUP, true, Instant.now(), Instant.now().plusSeconds(600)
                ));

        service().startAccountSetup(
                new StartAccountSetupRequest(user.getEmail(), "temporary-password"),
                REQUEST
        );

        verify(outboxService).enqueue(any(OutboxEnqueueRequest.class));
    }

    @Test
    void verifiedOtpReturnsOnlyRawTokenCreatedByChallengeService() {
        User user = user();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(otpChallengeService.verify(
                user.getEmail(), "123456", OtpPurpose.PASSWORD_RECOVERY, REQUEST
        )).thenReturn(new OtpChallengeService.VerificationResult(
                OtpChallengeService.Status.SUCCESS, user.getId(), "raw-reset-token"
        ));

        var response = service().verifyPasswordOtp(
                new VerifyPasswordOtpRequest(user.getEmail(), "123456"),
                REQUEST
        );

        assertEquals("raw-reset-token", response.resetToken());
    }

    @Test
    void passwordResetDelegatesToAtomicConsumer() {
        when(passwordResetService.consume("raw-token", "new-password", REQUEST))
                .thenReturn(PasswordResetService.ResetResult.SUCCESS);

        service().setPassword(new SetPasswordRequest("raw-token", "new-password"), REQUEST);

        verify(passwordResetService).consume("raw-token", "new-password", REQUEST);
    }

    private AuthService service() {
        return new AuthService(
                userRepository,
                passwordEncoder,
                jwtService,
                loginRateLimiter,
                securityUtils,
                userProfileService,
                outboxService,
                staffAuditEventRepository,
                otpRequestGuard,
                otpChallengeService,
                passwordResetService,
                securityAuditService,
                emailChangeOtpService,
                immediateTransactions()
        );
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setEmail("employee@example.net");
        user.setFullName("Employee Example");
        user.setPassword("stored-hash");
        user.setRole(Role.EMPLOYEE);
        user.setActive(true);
        user.setMustChangePassword(false);
        user.setEmailVerified(true);
        user.setSecurityVersion(1);
        return user;
    }
}
