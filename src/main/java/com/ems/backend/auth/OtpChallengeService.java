package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.security.TokenHashingService;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

@Service
public class OtpChallengeService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHashingService tokenHashingService;
    private final OtpSecurityProperties properties;
    private final SecurityAuditService auditService;

    public OtpChallengeService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenHashingService tokenHashingService,
            OtpSecurityProperties properties,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHashingService = tokenHashingService;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public IssuedOtp issue(Long userId, OtpPurpose purpose, RequestMetadata metadata) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return null;
        }
        if (purpose == OtpPurpose.ACCOUNT_SETUP && !Boolean.TRUE.equals(user.getMustChangePassword())) {
            return null;
        }
        if (purpose == OtpPurpose.PASSWORD_RECOVERY && Boolean.TRUE.equals(user.getMustChangePassword())) {
            return null;
        }

        Instant now = Instant.now();
        String otp = "%06d".formatted(SECURE_RANDOM.nextInt(1_000_000));
        user.setPasswordOtpHash(passwordEncoder.encode(otp));
        user.setPasswordOtpExpiresAt(now.plusSeconds(properties.validitySeconds()));
        user.setPasswordOtpFailedAttempts(0);
        user.setPasswordOtpIssuedAt(now);
        user.setPasswordOtpPurpose(purpose.name());
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);
        auditService.record(
                null, user.getId(), "OTP_ISSUED", "SUCCESS", purpose.name(),
                user.getEmail(), metadata
        );
        return new IssuedOtp(user.getId(), user.getEmail(), user.getFullName(), otp, purpose, true,
                user.getPasswordOtpIssuedAt(), user.getPasswordOtpExpiresAt());
    }

    @Transactional
    public VerificationResult verify(
            String normalizedEmail,
            String suppliedOtp,
            OtpPurpose expectedPurpose,
            RequestMetadata metadata
    ) {
        User user = userRepository.findByEmailForUpdate(normalizedEmail).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return VerificationResult.invalid(null);
        }

        Instant now = Instant.now();
        if (user.getPasswordOtpHash() == null
                || user.getPasswordOtpExpiresAt() == null
                || user.getPasswordOtpExpiresAt().isBefore(now)
                || !expectedPurpose.name().equals(user.getPasswordOtpPurpose())) {
            clearOtp(user);
            userRepository.save(user);
            auditService.record(
                    null, user.getId(), "OTP_VERIFICATION_FAILED", "DENIED", "MISSING_OR_EXPIRED",
                    normalizedEmail, metadata
            );
            return VerificationResult.expired(user.getId());
        }

        int failedAttempts = user.getPasswordOtpFailedAttempts() == null
                ? 0
                : user.getPasswordOtpFailedAttempts();
        if (failedAttempts >= properties.maximumVerificationAttempts()) {
            clearOtp(user);
            user.setPasswordOtpFailedAttempts(properties.maximumVerificationAttempts());
            userRepository.save(user);
            return VerificationResult.locked(user.getId());
        }

        if (!passwordEncoder.matches(suppliedOtp, user.getPasswordOtpHash())) {
            int nextAttempts = failedAttempts + 1;
            user.setPasswordOtpFailedAttempts(nextAttempts);
            boolean locked = nextAttempts >= properties.maximumVerificationAttempts();
            if (locked) {
                clearOtp(user);
                user.setPasswordOtpFailedAttempts(properties.maximumVerificationAttempts());
            }
            userRepository.save(user);
            auditService.record(
                    null, user.getId(), locked ? "OTP_LOCKED" : "OTP_VERIFICATION_FAILED",
                    "DENIED", locked ? "MAX_ATTEMPTS" : "INVALID_CODE", normalizedEmail, metadata
            );
            return locked
                    ? VerificationResult.locked(user.getId())
                    : VerificationResult.invalid(user.getId());
        }

        String rawResetToken = tokenHashingService.newBearerToken();
        user.setPasswordResetTokenHash(tokenHashingService.sha256(rawResetToken));
        user.setPasswordResetExpiresAt(now.plusSeconds(properties.resetTokenValiditySeconds()));
        clearOtp(user);
        userRepository.save(user);
        auditService.record(
                null, user.getId(), "OTP_VERIFIED", "SUCCESS", expectedPurpose.name(),
                normalizedEmail, metadata
        );
        return VerificationResult.success(user.getId(), rawResetToken);
    }

    private void clearOtp(User user) {
        user.setPasswordOtpHash(null);
        user.setPasswordOtpExpiresAt(null);
        user.setPasswordOtpFailedAttempts(0);
        user.setPasswordOtpIssuedAt(null);
        user.setPasswordOtpPurpose(null);
    }

    public record IssuedOtp(
            Long userId,
            String email,
            String fullName,
            String rawOtp,
            OtpPurpose purpose,
            boolean created,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    public record VerificationResult(Status status, Long userId, String rawResetToken) {
        static VerificationResult success(Long userId, String token) {
            return new VerificationResult(Status.SUCCESS, userId, token);
        }

        static VerificationResult invalid(Long userId) {
            return new VerificationResult(Status.INVALID, userId, null);
        }

        static VerificationResult expired(Long userId) {
            return new VerificationResult(Status.EXPIRED, userId, null);
        }

        static VerificationResult locked(Long userId) {
            return new VerificationResult(Status.LOCKED, userId, null);
        }
    }

    public enum Status {
        SUCCESS,
        INVALID,
        EXPIRED,
        LOCKED
    }
}
