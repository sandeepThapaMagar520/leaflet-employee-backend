package com.ems.backend.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(
        UUID id,
        UUID eventId,
        String eventType,
        Long recipientUserId,
        byte[] recipientCiphertext,
        String recipientHash,
        String templateKey,
        byte[] payloadCiphertext,
        boolean sensitivePayload,
        Instant expiresAt,
        int attemptCount,
        int maxAttempts,
        String correlationId
) {
}
