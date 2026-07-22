package com.ems.backend.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {
    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    private final OutboxRepository repository;
    private final OutboxCryptoService crypto;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, OutboxCryptoService crypto,
                         OutboxProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.crypto = crypto;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DeliveryStatus enqueue(OutboxEnqueueRequest request) {
        try {
            String normalized = request.recipientAddress().trim().toLowerCase();
            String recipientHash = crypto.hash(normalized);
            String idempotency = request.eventId() + ":EMAIL:" + request.templateKey() + ":" + recipientHash;
            Instant expiresAt = request.expiresAt();
            if (request.sensitive()) {
                Instant retentionLimit = Instant.now().plus(properties.sensitiveRetention());
                expiresAt = expiresAt == null || retentionLimit.isBefore(expiresAt) ? retentionLimit : expiresAt;
            }
            boolean inserted = repository.insert(new OutboxRepository.OutboxMessageDraft(
                    UUID.randomUUID(), request.eventId(), request.eventType(), request.recipientUserId(),
                    recipientHash, crypto.encrypt(normalized), request.templateKey(),
                    crypto.encrypt(objectMapper.writeValueAsString(request.payload())), request.sensitive(),
                    expiresAt, request.priority(), properties.maxAttempts(), Instant.now(),
                    idempotency, request.correlationId(), "PENDING"
            ));
            if (!inserted) log.info("outbox_duplicate_prevented eventId={} eventType={}", request.eventId(), request.eventType());
            return DeliveryStatus.QUEUED;
        } catch (Exception exception) {
            throw new IllegalStateException("Required delivery could not be queued", exception);
        }
    }

    public DeliveryStatus recordSuppressed(OutboxEnqueueRequest request) {
        try {
            String normalized = request.recipientAddress().trim().toLowerCase();
            String recipientHash = crypto.hash(normalized);
            String idempotency = request.eventId() + ":EMAIL:" + request.templateKey() + ":" + recipientHash;
            repository.insert(new OutboxRepository.OutboxMessageDraft(
                    UUID.randomUUID(), request.eventId(), request.eventType(), request.recipientUserId(),
                    recipientHash, crypto.encrypt(normalized), request.templateKey(), crypto.encrypt("{}"),
                    false, null, request.priority(), properties.maxAttempts(), Instant.now(),
                    idempotency, request.correlationId(), "SUPPRESSED"
            ));
            return DeliveryStatus.SUPPRESSED;
        } catch (Exception exception) {
            throw new IllegalStateException("Suppressed delivery decision could not be recorded", exception);
        }
    }

    public String recipient(OutboxMessage message) { return crypto.decrypt(message.recipientCiphertext()); }

    public Map<String, Object> payload(OutboxMessage message) {
        try {
            return objectMapper.readValue(crypto.decrypt(message.payloadCiphertext()), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox payload is invalid", exception);
        }
    }
}
