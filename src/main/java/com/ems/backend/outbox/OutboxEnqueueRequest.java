package com.ems.backend.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEnqueueRequest(
        UUID eventId,
        String eventType,
        Long recipientUserId,
        String recipientAddress,
        String templateKey,
        Map<String, Object> payload,
        boolean sensitive,
        Instant expiresAt,
        int priority,
        String correlationId
) {
}
