package com.ems.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityAuditService {
    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TokenHashingService tokenHashingService;

    public SecurityAuditService(JdbcTemplate jdbcTemplate, TokenHashingService tokenHashingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenHashingService = tokenHashingService;
    }

    public void record(
            Long actorUserId,
            Long targetUserId,
            String eventType,
            String outcome,
            String reasonCode,
            String normalizedIdentifier,
            RequestMetadata metadata
    ) {
        recordWithDetails(
                actorUserId, targetUserId, eventType, outcome, reasonCode, null,
                normalizedIdentifier, metadata
        );
    }

    public void recordWithDetails(
            Long actorUserId,
            Long targetUserId,
            String eventType,
            String outcome,
            String reasonCode,
            String details,
            String normalizedIdentifier,
            RequestMetadata metadata
    ) {
        jdbcTemplate.update("""
                INSERT INTO security_audit_events (
                    actor_user_id, target_user_id, event_type, outcome, reason_code, details,
                    account_identifier_hash, client_ip, user_agent, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                actorUserId,
                targetUserId,
                eventType,
                outcome,
                reasonCode,
                details == null ? null : details.substring(0, Math.min(details.length(), 500)),
                normalizedIdentifier == null ? null : tokenHashingService.sha256(normalizedIdentifier),
                metadata == null ? null : metadata.clientIp(),
                metadata == null ? null : metadata.userAgent(),
                metadata == null ? null : metadata.correlationId()
        );
    }

    public void recordBestEffort(
            Long targetUserId,
            String eventType,
            String reasonCode,
            String normalizedIdentifier,
            RequestMetadata metadata
    ) {
        recordBestEffort(
                null, targetUserId, eventType, "DENIED", reasonCode, normalizedIdentifier, metadata
        );
    }

    public void recordBestEffort(
            Long actorUserId,
            Long targetUserId,
            String eventType,
            String outcome,
            String reasonCode,
            String normalizedIdentifier,
            RequestMetadata metadata
    ) {
        try {
            record(
                    actorUserId, targetUserId, eventType, outcome, reasonCode,
                    normalizedIdentifier, metadata
            );
        } catch (RuntimeException exception) {
            log.warn("Could not persist security event type={} reason={}", eventType, reasonCode);
        }
    }
}
