package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.security.TokenHashingService;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHashingService tokenHashingService;
    private final SecurityAuditService auditService;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenHashingService tokenHashingService,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHashingService = tokenHashingService;
        this.auditService = auditService;
    }

    @Transactional
    public ResetResult consume(String rawToken, String newPassword, RequestMetadata metadata) {
        String tokenHash = tokenHashingService.sha256(rawToken);
        User user = userRepository.findByPasswordResetTokenHashForUpdate(tokenHash).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return ResetResult.INVALID;
        }
        if (user.getPasswordResetExpiresAt() == null
                || user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            clearResetToken(user);
            userRepository.save(user);
            auditService.record(
                    null, user.getId(), "PASSWORD_RESET_FAILED", "DENIED", "TOKEN_EXPIRED",
                    user.getEmail(), metadata
            );
            return ResetResult.EXPIRED;
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setEmailVerified(true);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        clearResetToken(user);
        userRepository.save(user);
        auditService.record(
                null, user.getId(), "PASSWORD_RESET_COMPLETED", "SUCCESS", "TOKEN_CONSUMED",
                user.getEmail(), metadata
        );
        return ResetResult.SUCCESS;
    }

    private void clearResetToken(User user) {
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
    }

    public enum ResetResult {
        SUCCESS,
        INVALID,
        EXPIRED
    }
}
