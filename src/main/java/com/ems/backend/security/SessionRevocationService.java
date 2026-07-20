package com.ems.backend.security;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionRevocationService {
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final SecurityAuditService auditService;

    public SessionRevocationService(
            UserRepository userRepository,
            SecurityUtils securityUtils,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.auditService = auditService;
    }

    @Transactional
    public void revokeOwnSessions(RequestMetadata metadata) {
        User authenticated = securityUtils.getCurrentUser();
        User locked = userRepository.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is no longer valid."));
        locked.setSecurityVersion(locked.getSecurityVersion() + 1);
        userRepository.save(locked);
        auditService.record(
                locked.getId(), locked.getId(), "SESSIONS_REVOKED", "SUCCESS", "SELF_REVOKE_ALL",
                locked.getEmail(), metadata
        );
    }

    @Transactional
    public void revokeUserSessions(Long targetUserId, String reason, RequestMetadata metadata) {
        User actor = securityUtils.getCurrentUser();
        User target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
        target.setSecurityVersion(target.getSecurityVersion() + 1);
        userRepository.save(target);
        auditService.recordWithDetails(
                actor.getId(), target.getId(), "SESSIONS_REVOKED", "SUCCESS",
                reason == null || reason.isBlank() ? "ADMIN_REVOKE_ALL" : "ADMIN_REVOKE_ALL_WITH_REASON",
                reason == null || reason.isBlank() ? null : reason.trim(),
                target.getEmail(), metadata
        );
    }
}
