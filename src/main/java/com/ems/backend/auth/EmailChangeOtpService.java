package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

@Service
public class EmailChangeOtpService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpSecurityProperties properties;
    private final SecurityAuditService auditService;

    public EmailChangeOtpService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            OtpSecurityProperties properties,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public IssuedEmailChange issue(Long userId, String newEmail, RequestMetadata metadata) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return null;
        }
        String otp = "%06d".formatted(SECURE_RANDOM.nextInt(1_000_000));
        user.setPendingEmail(newEmail);
        user.setEmailChangeOtpHash(passwordEncoder.encode(otp));
        user.setEmailChangeOtpExpiresAt(Instant.now().plusSeconds(properties.validitySeconds()));
        user.setEmailChangeOtpFailedAttempts(0);
        user.setEmailChangeOtpIssuedAt(Instant.now());
        userRepository.save(user);
        auditService.record(
                user.getId(), user.getId(), "EMAIL_CHANGE_OTP_ISSUED", "SUCCESS", null,
                user.getEmail(), metadata
        );
        return new IssuedEmailChange(user.getId(), newEmail, user.getFullName(), otp);
    }

    @Transactional
    public VerificationResult verify(Long userId, String suppliedOtp, RequestMetadata metadata) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return VerificationResult.invalid();
        }
        int attempts = user.getEmailChangeOtpFailedAttempts() == null
                ? 0
                : user.getEmailChangeOtpFailedAttempts();
        if (user.getPendingEmail() == null
                || user.getEmailChangeOtpHash() == null
                || user.getEmailChangeOtpExpiresAt() == null
                || user.getEmailChangeOtpExpiresAt().isBefore(Instant.now())
                || attempts >= properties.maximumVerificationAttempts()) {
            clear(user);
            userRepository.save(user);
            return VerificationResult.invalid();
        }
        if (!passwordEncoder.matches(suppliedOtp, user.getEmailChangeOtpHash())) {
            int next = attempts + 1;
            user.setEmailChangeOtpFailedAttempts(next);
            if (next >= properties.maximumVerificationAttempts()) {
                clear(user);
                user.setEmailChangeOtpFailedAttempts(properties.maximumVerificationAttempts());
            }
            userRepository.save(user);
            auditService.record(
                    user.getId(), user.getId(),
                    next >= properties.maximumVerificationAttempts()
                            ? "EMAIL_CHANGE_OTP_LOCKED"
                            : "EMAIL_CHANGE_OTP_FAILED",
                    "DENIED",
                    next >= properties.maximumVerificationAttempts() ? "MAX_ATTEMPTS" : "INVALID_CODE",
                    user.getEmail(), metadata
            );
            return VerificationResult.invalid();
        }
        if (userRepository.existsByEmailIgnoreCase(user.getPendingEmail())) {
            clear(user);
            userRepository.save(user);
            return VerificationResult.conflict();
        }

        user.setEmail(user.getPendingEmail());
        user.setEmailVerified(true);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        clear(user);
        User saved = userRepository.save(user);
        auditService.record(
                saved.getId(), saved.getId(), "ACCOUNT_EMAIL_CHANGED", "SUCCESS", "OTP_VERIFIED",
                saved.getEmail(), metadata
        );
        return VerificationResult.success(saved);
    }

    private void clear(User user) {
        user.setPendingEmail(null);
        user.setEmailChangeOtpHash(null);
        user.setEmailChangeOtpExpiresAt(null);
        user.setEmailChangeOtpFailedAttempts(0);
        user.setEmailChangeOtpIssuedAt(null);
    }

    public record IssuedEmailChange(Long userId, String email, String fullName, String rawOtp) {
    }

    public record VerificationResult(Status status, User user) {
        static VerificationResult success(User user) {
            return new VerificationResult(Status.SUCCESS, user);
        }

        static VerificationResult invalid() {
            return new VerificationResult(Status.INVALID, null);
        }

        static VerificationResult conflict() {
            return new VerificationResult(Status.CONFLICT, null);
        }
    }

    public enum Status {
        SUCCESS,
        INVALID,
        CONFLICT
    }
}
