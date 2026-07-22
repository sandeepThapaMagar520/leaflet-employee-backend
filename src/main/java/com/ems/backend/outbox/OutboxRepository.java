package com.ems.backend.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(OutboxMessageDraft draft) {
        return jdbc.update("""
                INSERT INTO outbox_messages (
                    id, event_id, event_type, channel, recipient_user_id,
                    recipient_address_hash, recipient_address_ciphertext, template_key,
                    payload_ciphertext, sensitive_payload, expires_at, priority,
                    max_attempts, available_at, idempotency_key, correlation_id, status
                ) VALUES (?, ?, ?, 'EMAIL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                draft.id(), draft.eventId(), draft.eventType(), draft.recipientUserId(),
                draft.recipientHash(), draft.recipientCiphertext(), draft.templateKey(),
                draft.payloadCiphertext(), draft.sensitive(), timestamp(draft.expiresAt()),
                draft.priority(), draft.maxAttempts(), Timestamp.from(draft.availableAt()),
                draft.idempotencyKey(), draft.correlationId(), draft.initialStatus()
        ) == 1;
    }

    @Transactional
    public List<OutboxMessage> claim(int limit, String workerId, Instant staleBefore) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM outbox_messages
                WHERE ((status IN ('PENDING','RETRY') AND available_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PROCESSING' AND locked_at < ?))
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                ORDER BY priority DESC, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, UUID.class, Timestamp.from(staleBefore), limit);
        if (ids.isEmpty()) return List.of();
        for (UUID id : ids) {
            jdbc.update("""
                    UPDATE outbox_messages
                    SET status='PROCESSING', locked_at=CURRENT_TIMESTAMP, locked_by=?, updated_at=CURRENT_TIMESTAMP,
                        version=version+1
                    WHERE id=?
                    """, workerId, id);
        }
        return ids.stream().map(this::getClaimed).toList();
    }

    private OutboxMessage getClaimed(UUID id) {
        return jdbc.queryForObject("""
                SELECT id,event_id,event_type,recipient_user_id,recipient_address_ciphertext,
                       recipient_address_hash,template_key,payload_ciphertext,sensitive_payload,
                       expires_at,attempt_count,max_attempts,correlation_id
                FROM outbox_messages WHERE id=?
                """, (rs, row) -> new OutboxMessage(
                rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getObject("recipient_user_id", Long.class),
                rs.getBytes("recipient_address_ciphertext"), rs.getString("recipient_address_hash"),
                rs.getString("template_key"), rs.getBytes("payload_ciphertext"),
                rs.getBoolean("sensitive_payload"), instant(rs.getTimestamp("expires_at")),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("correlation_id")
        ), id);
    }

    @Transactional
    public void markSent(OutboxMessage message, String workerId, String providerMessageId, Instant startedAt) {
        int attempt = message.attemptCount() + 1;
        int updated = jdbc.update("""
                UPDATE outbox_messages SET status='SENT',attempt_count=?,sent_at=CURRENT_TIMESTAMP,
                    provider_message_id=?,locked_at=NULL,locked_by=NULL,last_error_code=NULL,
                    last_error_summary=NULL,payload_ciphertext=CASE WHEN sensitive_payload THEN '\\x'::bytea ELSE payload_ciphertext END,
                    updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND status='PROCESSING' AND locked_by=?
                """, attempt, providerMessageId, message.id(), workerId);
        if (updated == 1) attempt(message.id(), attempt, workerId, "ACCEPTED", null, null, providerMessageId, startedAt);
    }

    @Transactional
    public void markFailure(OutboxMessage message, String workerId, boolean retryable, String code,
                            String summary, Instant nextAttempt, Instant startedAt) {
        int attempt = message.attemptCount() + 1;
        boolean retry = retryable && attempt < message.maxAttempts();
        int updated = jdbc.update("""
                UPDATE outbox_messages SET status=?,attempt_count=?,available_at=?,failed_at=?,
                    last_error_code=?,last_error_summary=?,locked_at=NULL,locked_by=NULL,
                    payload_ciphertext=CASE WHEN sensitive_payload AND NOT ? THEN '\\x'::bytea ELSE payload_ciphertext END,
                    updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND status='PROCESSING' AND locked_by=?
                """, retry ? "RETRY" : "FAILED", attempt,
                Timestamp.from(retry ? nextAttempt : Instant.now()), retry ? null : Timestamp.from(Instant.now()),
                code, safe(summary), retry, message.id(), workerId);
        if (updated == 1) {
            attempt(message.id(), attempt, workerId, retryable ? "RETRYABLE_FAILURE" : "PERMANENT_FAILURE",
                    code, summary, null, startedAt);
        }
    }

    @Transactional
    public void markExpired(OutboxMessage message, String workerId, Instant startedAt) {
        int attempt = message.attemptCount() + 1;
        int updated = jdbc.update("""
                UPDATE outbox_messages SET status='EXPIRED',attempt_count=?,failed_at=CURRENT_TIMESTAMP,
                    locked_at=NULL,locked_by=NULL,payload_ciphertext='\\x'::bytea,updated_at=CURRENT_TIMESTAMP,
                    version=version+1 WHERE id=? AND status='PROCESSING' AND locked_by=?
                """, attempt, message.id(), workerId);
        if (updated == 1) {
            attempt(message.id(), attempt, workerId, "EXPIRED", "MESSAGE_EXPIRED", "Delivery eligibility expired", null, startedAt);
        }
    }

    private void attempt(UUID id, int number, String worker, String outcome, String code,
                         String summary, String providerId, Instant startedAt) {
        jdbc.update("""
                INSERT INTO outbox_delivery_attempts(outbox_message_id,attempt_number,worker_id,outcome,
                    error_code,error_summary,provider_message_id,started_at,completed_at)
                VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING
                """, id, number, worker, outcome, code, safe(summary), providerId, Timestamp.from(startedAt));
    }

    public int expireDueMessages(Instant staleBefore) {
        return jdbc.update("""
                UPDATE outbox_messages SET status='EXPIRED',failed_at=CURRENT_TIMESTAMP,
                    payload_ciphertext='\\x'::bytea,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE expires_at <= CURRENT_TIMESTAMP
                  AND (status IN ('PENDING','RETRY') OR (status='PROCESSING' AND locked_at < ?))
                """, Timestamp.from(staleBefore));
    }

    public int cleanup(Instant sentBefore, Instant terminalBefore) {
        return jdbc.update("""
                DELETE FROM outbox_messages WHERE id IN (
                    SELECT id FROM outbox_messages
                    WHERE (status='SENT' AND sent_at < ?)
                       OR (status IN ('CANCELLED','EXPIRED','SUPPRESSED') AND updated_at < ?)
                    ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT 500
                )
                """, Timestamp.from(sentBefore), Timestamp.from(terminalBefore));
    }

    public List<OutboxAdminMessage> listFailed(int page, int size) {
        return jdbc.query("""
                SELECT id,event_id,event_type,template_key,recipient_user_id,recipient_address_hash,status,
                       attempt_count,max_attempts,available_at,failed_at,last_error_code,last_error_summary,
                       provider_message_id,created_at,updated_at,expires_at
                FROM outbox_messages WHERE status='FAILED'
                ORDER BY failed_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new OutboxAdminMessage(
                rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                rs.getString("template_key"), rs.getObject("recipient_user_id", Long.class),
                rs.getString("recipient_address_hash"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), instant(rs.getTimestamp("available_at")), instant(rs.getTimestamp("failed_at")),
                rs.getString("last_error_code"), rs.getString("last_error_summary"), rs.getString("provider_message_id"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("expires_at"))
        ), size, page * size);
    }

    public long failedCount() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE status='FAILED'", Long.class);
        return value == null ? 0 : value;
    }

    public List<OutboxAttempt> attempts(UUID messageId) {
        return jdbc.query("""
                SELECT attempt_number,worker_id,outcome,error_code,error_summary,provider_message_id,started_at,completed_at
                FROM outbox_delivery_attempts WHERE outbox_message_id=? ORDER BY attempt_number DESC
                """, (rs, row) -> new OutboxAttempt(rs.getInt("attempt_number"), rs.getString("worker_id"),
                rs.getString("outcome"), rs.getString("error_code"), rs.getString("error_summary"),
                rs.getString("provider_message_id"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at"))), messageId);
    }

    public int retryFailed(UUID id) {
        return jdbc.update("""
                UPDATE outbox_messages SET status='RETRY',available_at=CURRENT_TIMESTAMP,failed_at=NULL,
                    last_error_code=NULL,last_error_summary=NULL,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND status='FAILED' AND payload_ciphertext <> '\\x'::bytea
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                  AND attempt_count < max_attempts
                """, id);
    }

    public int cancel(UUID id) {
        return jdbc.update("""
                UPDATE outbox_messages SET status='CANCELLED',failed_at=CURRENT_TIMESTAMP,
                    payload_ciphertext=CASE WHEN sensitive_payload THEN '\\x'::bytea ELSE payload_ciphertext END,
                    locked_at=NULL,locked_by=NULL,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND status IN ('PENDING','RETRY')
                """, id);
    }

    public Map<String, Object> queueStats() {
        return jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status='PENDING') AS pending,
                       count(*) FILTER (WHERE status='RETRY') AS retry,
                       count(*) FILTER (WHERE status='FAILED') AS failed,
                       count(*) FILTER (WHERE status='PROCESSING') AS processing,
                       COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-min(created_at) FILTER
                           (WHERE status IN ('PENDING','RETRY')))),0)::bigint AS oldest_pending_seconds,
                       (SELECT count(*) FROM outbox_delivery_attempts WHERE outcome='ACCEPTED'
                           AND completed_at > CURRENT_TIMESTAMP-INTERVAL '24 hours') AS accepted_24h,
                       (SELECT count(*) FROM outbox_delivery_attempts WHERE outcome='RETRYABLE_FAILURE'
                           AND completed_at > CURRENT_TIMESTAMP-INTERVAL '24 hours') AS retryable_failures_24h,
                       (SELECT count(*) FROM outbox_delivery_attempts WHERE outcome='PERMANENT_FAILURE'
                           AND completed_at > CURRENT_TIMESTAMP-INTERVAL '24 hours') AS permanent_failures_24h
                FROM outbox_messages
                """);
    }

    public long staleProcessingCount(Instant staleBefore) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE status='PROCESSING' AND locked_at < ?",
                Long.class, Timestamp.from(staleBefore));
        return count == null ? 0 : count;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String safe(String value) { return value == null ? null : value.substring(0, Math.min(300, value.length())); }

    public record OutboxMessageDraft(UUID id, UUID eventId, String eventType, Long recipientUserId,
            String recipientHash, byte[] recipientCiphertext, String templateKey, byte[] payloadCiphertext,
            boolean sensitive, Instant expiresAt, int priority, int maxAttempts, Instant availableAt,
            String idempotencyKey, String correlationId, String initialStatus) {}

    public record OutboxAdminMessage(UUID id, UUID eventId, String eventType, String templateKey,
            Long recipientUserId, String recipientAddressHash, String status, int attemptCount,
            int maxAttempts, Instant availableAt, Instant failedAt, String lastErrorCode,
            String lastErrorSummary, String providerMessageId, Instant createdAt, Instant updatedAt,
            Instant expiresAt) {}

    public record OutboxAttempt(int attemptNumber, String workerId, String outcome, String errorCode,
            String errorSummary, String providerMessageId, Instant startedAt, Instant completedAt) {}
}
