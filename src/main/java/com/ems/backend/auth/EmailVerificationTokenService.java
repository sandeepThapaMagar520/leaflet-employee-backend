package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.security.TokenHashingService;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class EmailVerificationTokenService {
    private final UserRepository userRepository;
    private final TokenHashingService tokenHashingService;
    private final SecurityAuditService auditService;

    public EmailVerificationTokenService(
            UserRepository userRepository,
            TokenHashingService tokenHashingService,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.tokenHashingService = tokenHashingService;
        this.auditService = auditService;
    }

    @Transactional
    public IssuedVerification issue(Long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return null;
        }
        String rawToken = tokenHashingService.newBearerToken();
        user.setEmailVerificationTokenHash(tokenHashingService.sha256(rawToken));
        user.setEmailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        user.setEmailVerified(false);
        userRepository.save(user);
        return new IssuedVerification(user.getId(), user.getEmail(), user.getFullName(), rawToken, user.getEmailVerificationExpiresAt());
    }

    @Transactional
    public VerificationResult consume(String rawToken, RequestMetadata metadata) {
        String tokenHash = tokenHashingService.sha256(rawToken);
        User user = userRepository.findByEmailVerificationTokenHashForUpdate(tokenHash).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return VerificationResult.INVALID;
        }
        if (user.getEmailVerificationExpiresAt() == null
                || user.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            clear(user);
            userRepository.save(user);
            return VerificationResult.EXPIRED;
        }
        user.setEmailVerified(true);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        clear(user);
        userRepository.save(user);
        auditService.record(
                user.getId(), user.getId(), "EMAIL_VERIFIED", "SUCCESS", "TOKEN_CONSUMED",
                user.getEmail(), metadata
        );
        return VerificationResult.SUCCESS;
    }

    private void clear(User user) {
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationExpiresAt(null);
    }

    public record IssuedVerification(Long userId, String email, String fullName, String rawToken, Instant expiresAt) {
    }

    public enum VerificationResult {
        SUCCESS,
        INVALID,
        EXPIRED
    }
}
