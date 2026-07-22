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
import com.ems.backend.auth.dto.StartAccountSetupRequest;
import com.ems.backend.auth.dto.VerifyEmailChange;
import com.ems.backend.auth.dto.VerifyPasswordOtpRequest;
import com.ems.backend.notification.EventIds;
import com.ems.backend.outbox.OutboxEnqueueRequest;
import com.ems.backend.outbox.OutboxService;
import com.ems.backend.security.JwtService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.User;
import com.ems.backend.user.EmploymentType;
import com.ems.backend.user.StaffAuditAction;
import com.ems.backend.user.StaffAuditEvent;
import com.ems.backend.user.StaffAuditEventRepository;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final SecurityUtils securityUtils;
    private final UserProfileService userProfileService;
    private final OutboxService outboxService;
    private final StaffAuditEventRepository staffAuditEventRepository;
    private final OtpRequestGuard otpRequestGuard;
    private final OtpChallengeService otpChallengeService;
    private final PasswordResetService passwordResetService;
    private final SecurityAuditService securityAuditService;
    private final EmailChangeOtpService emailChangeOtpService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter,
            SecurityUtils securityUtils,
            UserProfileService userProfileService,
            OutboxService outboxService,
            StaffAuditEventRepository staffAuditEventRepository,
            OtpRequestGuard otpRequestGuard,
            OtpChallengeService otpChallengeService,
            PasswordResetService passwordResetService,
            SecurityAuditService securityAuditService,
            EmailChangeOtpService emailChangeOtpService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.securityUtils = securityUtils;
        this.userProfileService = userProfileService;
        this.outboxService = outboxService;
        this.staffAuditEventRepository = staffAuditEventRepository;
        this.otpRequestGuard = otpRequestGuard;
        this.otpChallengeService = otpChallengeService;
        this.passwordResetService = passwordResetService;
        this.securityAuditService = securityAuditService;
        this.emailChangeOtpService = emailChangeOtpService;
    }

    @Transactional
    public StaffRegistrationResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.temporaryPassword()));
        user.setRole(request.role());
        user.setJobTitle(request.jobTitle().trim());
        user.setEmployeeId(normalize(request.employeeId()));
        user.setJoiningDate(request.joiningDate());
        user.setEmploymentType(request.employmentType() == null ? EmploymentType.FULL_TIME : request.employmentType());
        user.setPhone(normalize(request.phone()));
        user.setEmergencyContact(normalize(request.emergencyContact()));
        user.setDepartment(normalize(request.department()));
        user.setLocation(normalize(request.location()));
        user.setMustChangePassword(true);
        user.setEmailVerified(false);

        User saved = userRepository.save(user);
        userProfileService.getOrCreateSettings(saved);
        auditRegistration(saved);
        var eventId = EventIds.stable("ACCOUNT_SETUP", saved.getId(), saved.getSecurityVersion());
        outboxService.enqueue(new OutboxEnqueueRequest(
                eventId, "ACCOUNT_SETUP", saved.getId(), saved.getEmail(), "ACCOUNT_SETUP",
                Map.of("fullName", saved.getFullName(), "temporaryPassword", request.temporaryPassword()),
                true, Instant.now().plus(24, ChronoUnit.HOURS), 100, correlationId(RequestMetadata.current())
        ));

        return new StaffRegistrationResponse(
                saved.getId(),
                saved.getFullName(),
                saved.getEmail(),
                saved.getRole(),
                com.ems.backend.outbox.DeliveryStatus.QUEUED,
                "Staff registered. The temporary password and setup link are queued for delivery to " + saved.getEmail() + "."
        );
    }

    @Transactional
    public void startAccountSetup(StartAccountSetupRequest request, RequestMetadata metadata) {
        String email = request.email().trim().toLowerCase();
        otpRequestGuard.checkIssuance(email, metadata);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> invalidSetup(email, metadata));

        if (Boolean.FALSE.equals(user.getActive())
                || !Boolean.TRUE.equals(user.getMustChangePassword())
                || !passwordEncoder.matches(request.temporaryPassword(), user.getPassword())) {
            throw invalidSetup(email, metadata);
        }

        OtpChallengeService.IssuedOtp issued =
                otpChallengeService.issue(user.getId(), OtpPurpose.ACCOUNT_SETUP, metadata);
        if (issued == null || !issued.created() || issued.rawOtp() == null) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another verification code."
            );
        }
        outboxService.enqueue(new OutboxEnqueueRequest(
                EventIds.stable("ACCOUNT_SETUP_OTP", issued.userId(), issued.issuedAt()),
                "ACCOUNT_SETUP_OTP", issued.userId(), issued.email(), "ACCOUNT_SETUP_OTP",
                Map.of("fullName", issued.fullName(), "otp", issued.rawOtp()), true,
                issued.expiresAt(), 100, correlationId(metadata)
        ));
    }

    public AuthResponse login(LoginRequest request, RequestMetadata metadata) {
        String email = request.email().trim().toLowerCase();
        loginRateLimiter.checkAllowed(email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    loginRateLimiter.recordFailure(email);
                    securityAuditService.recordBestEffort(
                            null, "LOGIN_FAILED", "INVALID_CREDENTIALS", email, metadata
                    );
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
                });

        if (Boolean.FALSE.equals(user.getActive())) {
            loginRateLimiter.recordFailure(email);
            securityAuditService.recordBestEffort(
                    user.getId(), "LOGIN_FAILED", "ACCOUNT_DISABLED", email, metadata
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(email);
            securityAuditService.recordBestEffort(
                    user.getId(), "LOGIN_FAILED", "INVALID_CREDENTIALS", email, metadata
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        if (Boolean.TRUE.equals(user.getMustChangePassword())) {
            securityAuditService.recordBestEffort(
                    null, user.getId(), "LOGIN_BLOCKED", "DENIED",
                    "ACCOUNT_SETUP_REQUIRED", email, metadata
            );
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Complete first-time account setup using your temporary password before signing in."
            );
        }

        loginRateLimiter.clear(email);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        securityAuditService.record(
                user.getId(), user.getId(), "LOGIN_SUCCEEDED", "SUCCESS", null, email, metadata
        );

        return buildAuthResponse(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request, RequestMetadata metadata) {
        User authenticated = securityUtils.getCurrentUser();
        User currentUser = userRepository.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is no longer valid."));
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        currentUser.setPassword(passwordEncoder.encode(request.newPassword()));
        currentUser.setPasswordChangedAt(Instant.now());
        currentUser.setMustChangePassword(false);
        currentUser.setSecurityVersion(currentUser.getSecurityVersion() + 1);
        userRepository.save(currentUser);
        securityAuditService.record(
                currentUser.getId(), currentUser.getId(), "PASSWORD_CHANGED", "SUCCESS",
                "AUTHENTICATED_CHANGE", currentUser.getEmail(), metadata
        );
    }

    @Transactional
    public void requestEmailChange(RequestEmailChange request, RequestMetadata metadata) {
        User currentUser = securityUtils.getCurrentUser();
        String newEmail = request.newEmail().trim().toLowerCase();
        otpRequestGuard.checkIssuance(newEmail, metadata);
        if (newEmail.equals(currentUser.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is already your current email address.");
        }
        if (userRepository.existsByEmailIgnoreCase(newEmail)
                || userRepository.existsByPendingEmailIgnoreCase(newEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists.");
        }

        EmailChangeOtpService.IssuedEmailChange issued =
                emailChangeOtpService.issue(currentUser.getId(), newEmail, metadata);
        if (issued == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email change could not be started.");
        outboxService.enqueue(new OutboxEnqueueRequest(
                EventIds.stable("EMAIL_CHANGE_OTP", issued.userId(), issued.issuedAt()),
                "EMAIL_CHANGE_OTP", issued.userId(), issued.email(), "EMAIL_CHANGE_OTP",
                Map.of("fullName", issued.fullName(), "otp", issued.rawOtp()), true,
                issued.expiresAt(), 100, correlationId(metadata)
        ));
    }

    public AuthResponse verifyEmailChange(VerifyEmailChange request, RequestMetadata metadata) {
        User currentUser = securityUtils.getCurrentUser();
        otpRequestGuard.checkVerification(currentUser.getEmail(), metadata);
        EmailChangeOtpService.VerificationResult result =
                emailChangeOtpService.verify(currentUser.getId(), request.otp(), metadata);
        if (result.status() == EmailChangeOtpService.Status.CONFLICT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists.");
        }
        if (result.status() != EmailChangeOtpService.Status.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP.");
        }
        return buildAuthResponse(result.user());
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequest request, RequestMetadata metadata) {
        String email = request.email().trim().toLowerCase();
        otpRequestGuard.checkIssuance(email, metadata);
        securityAuditService.recordBestEffort(
                null, null, "PASSWORD_RESET_REQUESTED", "ACCEPTED",
                "REQUEST_ACCEPTED", email, metadata
        );
        userRepository.findByEmail(email)
                .filter(user -> !Boolean.FALSE.equals(user.getActive()))
                .filter(user -> !Boolean.TRUE.equals(user.getMustChangePassword()))
                .map(user -> otpChallengeService.issue(user.getId(), OtpPurpose.PASSWORD_RECOVERY, metadata))
                .filter(issued -> issued != null && issued.created() && issued.rawOtp() != null)
                .ifPresent(issued -> outboxService.enqueue(new OutboxEnqueueRequest(
                        EventIds.stable("PASSWORD_RECOVERY_OTP", issued.userId(), issued.issuedAt()),
                        "PASSWORD_RECOVERY_OTP", issued.userId(), issued.email(), "PASSWORD_RECOVERY_OTP",
                        Map.of("fullName", issued.fullName(), "otp", issued.rawOtp()), true,
                        issued.expiresAt(), 100, correlationId(metadata)
                )));
    }

    public OtpVerificationResponse verifyPasswordOtp(
            VerifyPasswordOtpRequest request,
            RequestMetadata metadata
    ) {
        String email = request.email().trim().toLowerCase();
        otpRequestGuard.checkVerification(email, metadata);
        OtpPurpose purpose = userRepository.findByEmail(email)
                .filter(user -> Boolean.TRUE.equals(user.getMustChangePassword()))
                .map(user -> OtpPurpose.ACCOUNT_SETUP)
                .orElse(OtpPurpose.PASSWORD_RECOVERY);
        OtpChallengeService.VerificationResult result =
                otpChallengeService.verify(email, request.otp(), purpose, metadata);
        if (result.status() != OtpChallengeService.Status.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP.");
        }

        return new OtpVerificationResponse(
                result.rawResetToken(),
                "OTP verified. You can now create your new password."
        );
    }

    public void setPassword(SetPasswordRequest request, RequestMetadata metadata) {
        PasswordResetService.ResetResult result =
                passwordResetService.consume(request.resetToken(), request.newPassword(), metadata);
        if (result != PasswordResetService.ResetResult.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password reset session is invalid or expired.");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void auditRegistration(User saved) {
        User actor = null;
        try {
            actor = securityUtils.getCurrentUser();
        } catch (RuntimeException ignored) {
            // Registration can be called in contexts where no authenticated actor is available.
        }
        StaffAuditEvent event = new StaffAuditEvent();
        event.setStaffUser(saved);
        event.setActor(actor);
        event.setAction(StaffAuditAction.REGISTERED);
        event.setDescription("Registered staff account and sent first-time setup invite.");
        staffAuditEventRepository.save(event);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return new AuthResponse(
                token,
                "Bearer",
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getEmailVerified(),
                user.getMustChangePassword(),
                user.getProfileMediaAsset() == null ? null : user.getProfilePhotoUrl()
        );
    }

    private ResponseStatusException invalidSetup(String email, RequestMetadata metadata) {
        securityAuditService.recordBestEffort(
                null, "ACCOUNT_SETUP_FAILED", "INVALID_CREDENTIALS", email, metadata
        );
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or temporary password.");
    }

    private String correlationId(RequestMetadata metadata) {
        return metadata == null ? null : metadata.correlationId();
    }
}
