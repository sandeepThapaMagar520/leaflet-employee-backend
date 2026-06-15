package com.ems.backend.auth;

import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.auth.dto.RequestEmailChange;
import com.ems.backend.auth.dto.SetPasswordRequest;
import com.ems.backend.auth.dto.StartAccountSetupRequest;
import com.ems.backend.auth.dto.VerifyEmailChange;
import com.ems.backend.auth.dto.VerifyPasswordOtpRequest;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.mail.EmailService;
import com.ems.backend.security.JwtService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginRejectsInactiveUser() {
        User user = activeUser();
        user.setActive(false);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class, () -> authService.login(new LoginRequest("admin@example.com", "password")));
        verify(loginRateLimiter).recordFailure("admin@example.com");
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    void loginSucceedsForActiveUser() {
        User user = activeUser();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtService.generateToken("admin@example.com", "ADMIN")).thenReturn("token-123");
        when(userRepository.save(user)).thenReturn(user);

        var response = authService.login(new LoginRequest("admin@example.com", "password"));

        assertEquals("token-123", response.accessToken());
        verify(loginRateLimiter).clear("admin@example.com");
    }

    @Test
    void loginRejectsAccountThatHasNotCompletedOtpSetup() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class,
                () -> authService.login(new LoginRequest("admin@example.com", "password")));

        verify(passwordEncoder, never()).matches("password", "encoded");
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    void startAccountSetupSendsOtpAfterTemporaryPasswordMatches() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("temporary-password", "encoded")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-otp");

        authService.startAccountSetup(
                new StartAccountSetupRequest("admin@example.com", "temporary-password")
        );

        assertEquals("encoded-otp", user.getPasswordOtp());
        assertNotNull(user.getPasswordOtpExpiresAt());
        verify(emailService).sendPasswordOtp(eq("admin@example.com"), eq("Admin"), anyString(), eq(true));
        verify(userRepository).save(user);
    }

    @Test
    void startAccountSetupRejectsIncorrectTemporaryPassword() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> authService.startAccountSetup(
                new StartAccountSetupRequest("admin@example.com", "wrong-password")
        ));

        verify(emailService, never()).sendPasswordOtp(anyString(), anyString(), anyString(), eq(true));
    }

    @Test
    void forgotPasswordCannotBypassFirstTimeTemporaryPassword() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        authService.requestPasswordReset(new com.ems.backend.auth.dto.PasswordResetRequest("admin@example.com"));

        verify(emailService, never()).sendPasswordOtp(anyString(), anyString(), anyString(), eq(true));
        verify(emailService, never()).sendPasswordOtp(anyString(), anyString(), anyString(), eq(false));
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        User user = activeUser();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(new ChangePasswordRequest("wrong", "new-password")));
        verify(userRepository, never()).save(user);
    }

    @Test
    void changePasswordSavesEncodedPassword() {
        User user = activeUser();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

        authService.changePassword(new ChangePasswordRequest("password", "new-password"));

        assertEquals("new-encoded", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void requestEmailChangeSendsOtpToNewAddress() {
        User user = activeUser();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-email-otp");

        authService.requestEmailChange(new RequestEmailChange("new@example.com"));

        assertEquals("new@example.com", user.getPendingEmail());
        assertEquals("encoded-email-otp", user.getEmailChangeOtp());
        assertNotNull(user.getEmailChangeOtpExpiresAt());
        verify(emailService).sendEmailChangeOtp(eq("new@example.com"), eq("Admin"), anyString());
        verify(userRepository).save(user);
    }

    @Test
    void verifyEmailChangeUpdatesEmailAndReturnsNewToken() {
        User user = activeUser();
        user.setPendingEmail("new@example.com");
        user.setEmailChangeOtp("encoded-email-otp");
        user.setEmailChangeOtpExpiresAt(Instant.now().plusSeconds(60));
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.matches("123456", "encoded-email-otp")).thenReturn(true);
        when(jwtService.generateToken("new@example.com", "ADMIN")).thenReturn("new-token");

        var response = authService.verifyEmailChange(new VerifyEmailChange("123456"));

        assertEquals("new@example.com", user.getEmail());
        assertEquals("new-token", response.accessToken());
        assertEquals(null, user.getPendingEmail());
        assertEquals(null, user.getEmailChangeOtp());
        verify(userRepository).save(user);
    }

    @Test
    void verifyPasswordOtpReturnsTokenBeforePasswordCanBeSet() {
        User user = activeUser();
        user.setPasswordOtp("encoded-otp");
        user.setPasswordOtpExpiresAt(Instant.now().plusSeconds(60));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("123456", "encoded-otp")).thenReturn(true);

        var response = authService.verifyPasswordOtp(
                new VerifyPasswordOtpRequest("admin@example.com", "123456")
        );

        assertNotNull(response.resetToken());
        assertEquals(response.resetToken(), user.getPasswordResetToken());
        assertEquals(null, user.getPasswordOtp());
        verify(userRepository).save(user);
    }

    @Test
    void setPasswordRejectsMissingVerifiedResetToken() {
        when(userRepository.findByPasswordResetToken("invalid-token")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> authService.setPassword(new SetPasswordRequest("invalid-token", "new-password")));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void setPasswordCompletesAccountSetupWithVerifiedToken() {
        User user = activeUser();
        user.setMustChangePassword(true);
        user.setEmailVerified(false);
        user.setPasswordResetToken("verified-token");
        user.setPasswordResetExpiresAt(Instant.now().plusSeconds(60));
        when(userRepository.findByPasswordResetToken("verified-token")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

        authService.setPassword(new SetPasswordRequest("verified-token", "new-password"));

        assertEquals("new-encoded", user.getPassword());
        assertFalse(user.getMustChangePassword());
        assertTrue(user.getEmailVerified());
        assertEquals(null, user.getPasswordResetToken());
        verify(userRepository).save(user);
    }

    private static User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("admin@example.com");
        user.setFullName("Admin");
        user.setPassword("encoded");
        user.setRole(Role.ADMIN);
        user.setActive(true);
        return user;
    }
}
