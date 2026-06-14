package com.ems.backend.auth;

import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.auth.dto.AuthResponse;
import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.OtpVerificationResponse;
import com.ems.backend.auth.dto.PasswordResetRequest;
import com.ems.backend.auth.dto.RegisterRequest;
import com.ems.backend.auth.dto.RequestEmailChange;
import com.ems.backend.auth.dto.SetPasswordRequest;
import com.ems.backend.auth.dto.StaffRegistrationResponse;
import com.ems.backend.auth.dto.VerifyEmailChange;
import com.ems.backend.auth.dto.VerifyPasswordOtpRequest;
import com.ems.backend.mail.EmailService;
import com.ems.backend.security.JwtService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.User;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final SecurityUtils securityUtils;
    private final UserProfileService userProfileService;
    private final EmailService emailService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter,
            SecurityUtils securityUtils,
            UserProfileService userProfileService,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.securityUtils = securityUtils;
        this.userProfileService = userProfileService;
        this.emailService = emailService;
    }

    public StaffRegistrationResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole(request.role());
        user.setMustChangePassword(true);
        user.setEmailVerified(false);

        User saved = userRepository.save(user);
        userProfileService.getOrCreateSettings(saved);
        issuePasswordOtp(saved, true);

        return new StaffRegistrationResponse(
                saved.getId(),
                saved.getFullName(),
                saved.getEmail(),
                saved.getRole(),
                "Staff registered. A password setup OTP was sent to " + saved.getEmail() + "."
        );
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();
        loginRateLimiter.checkAllowed(email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    loginRateLimiter.recordFailure(email);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
                });

        if (Boolean.FALSE.equals(user.getActive())) {
            loginRateLimiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been deactivated. Contact your administrator.");
        }

        if (Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Complete your account setup using the OTP sent to your email before signing in."
            );
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        loginRateLimiter.clear(email);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    public void changePassword(ChangePasswordRequest request) {
        User currentUser = securityUtils.getCurrentUser();
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        currentUser.setPassword(passwordEncoder.encode(request.newPassword()));
        currentUser.setPasswordChangedAt(Instant.now());
        currentUser.setMustChangePassword(false);
        userRepository.save(currentUser);
    }

    public void requestEmailChange(RequestEmailChange request) {
        User currentUser = securityUtils.getCurrentUser();
        String newEmail = request.newEmail().trim().toLowerCase();
        if (newEmail.equals(currentUser.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is already your current email address.");
        }
        if (userRepository.existsByEmail(newEmail) || userRepository.existsByPendingEmail(newEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists.");
        }

        String otp = generateOtp();
        currentUser.setPendingEmail(newEmail);
        currentUser.setEmailChangeOtp(passwordEncoder.encode(otp));
        currentUser.setEmailChangeOtpExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        userRepository.save(currentUser);
        emailService.sendEmailChangeOtp(newEmail, currentUser.getFullName(), otp);
    }

    public AuthResponse verifyEmailChange(VerifyEmailChange request) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser.getPendingEmail() == null
                || currentUser.getEmailChangeOtp() == null
                || currentUser.getEmailChangeOtpExpiresAt() == null
                || currentUser.getEmailChangeOtpExpiresAt().isBefore(Instant.now())
                || !passwordEncoder.matches(request.otp(), currentUser.getEmailChangeOtp())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP.");
        }
        if (userRepository.existsByEmail(currentUser.getPendingEmail())) {
            clearEmailChange(currentUser);
            userRepository.save(currentUser);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists.");
        }

        currentUser.setEmail(currentUser.getPendingEmail());
        currentUser.setEmailVerified(true);
        clearEmailChange(currentUser);
        userRepository.save(currentUser);
        return buildAuthResponse(currentUser);
    }

    public void requestPasswordReset(PasswordResetRequest request) {
        userRepository.findByEmail(request.email().trim().toLowerCase())
                .filter(user -> !Boolean.FALSE.equals(user.getActive()))
                .ifPresent(user -> issuePasswordOtp(user, Boolean.TRUE.equals(user.getMustChangePassword())));
    }

    public OtpVerificationResponse verifyPasswordOtp(VerifyPasswordOtpRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP."));

        if (user.getPasswordOtp() == null
                || user.getPasswordOtpExpiresAt() == null
                || user.getPasswordOtpExpiresAt().isBefore(Instant.now())
                || !passwordEncoder.matches(request.otp(), user.getPasswordOtp())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP.");
        }

        String resetToken = UUID.randomUUID().toString();
        user.setPasswordOtp(null);
        user.setPasswordOtpExpiresAt(null);
        user.setPasswordResetToken(resetToken);
        user.setPasswordResetExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        userRepository.save(user);

        return new OtpVerificationResponse(
                resetToken,
                "OTP verified. You can now create your new password."
        );
    }

    public void setPassword(SetPasswordRequest request) {
        User user = userRepository.findByPasswordResetToken(request.resetToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password reset session is invalid or expired."));

        if (user.getPasswordResetExpiresAt() == null
                || user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            clearPasswordReset(user);
            userRepository.save(user);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password reset session is invalid or expired.");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setEmailVerified(true);
        clearPasswordReset(user);
        userRepository.save(user);
    }

    private void issuePasswordOtp(User user, boolean accountSetup) {
        String otp = generateOtp();
        user.setPasswordOtp(passwordEncoder.encode(otp));
        user.setPasswordOtpExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);
        emailService.sendPasswordOtp(user.getEmail(), user.getFullName(), otp, accountSetup);
    }

    private void clearPasswordReset(User user) {
        user.setPasswordOtp(null);
        user.setPasswordOtpExpiresAt(null);
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
    }

    private void clearEmailChange(User user) {
        user.setPendingEmail(null);
        user.setEmailChangeOtp(null);
        user.setEmailChangeOtpExpiresAt(null);
    }

    private String generateOtp() {
        return "%06d".formatted(SECURE_RANDOM.nextInt(1_000_000));
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(
                token,
                "Bearer",
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getEmailVerified(),
                user.getMustChangePassword(),
                user.getProfilePhotoUrl()
        );
    }
}
