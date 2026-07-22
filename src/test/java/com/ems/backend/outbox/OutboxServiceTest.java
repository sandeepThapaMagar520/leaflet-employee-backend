package com.ems.backend.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxServiceTest {
    @Test
    void encryptsRecipientAndOtpAndUsesStableIdempotency() {
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.insert(any())).thenReturn(true);
        OutboxProperties properties = properties();
        OutboxCryptoService crypto = new OutboxCryptoService(properties);
        OutboxService service = new OutboxService(repository, crypto, properties, new ObjectMapper());
        UUID eventId = UUID.randomUUID();

        service.enqueue(new OutboxEnqueueRequest(eventId, "PASSWORD_RECOVERY_OTP", 7L,
                "Employee@Example.com", "PASSWORD_RECOVERY_OTP",
                Map.of("otp", "123456"), true, Instant.now().plusSeconds(600), 100, "correlation"));

        ArgumentCaptor<OutboxRepository.OutboxMessageDraft> captor = ArgumentCaptor.forClass(OutboxRepository.OutboxMessageDraft.class);
        verify(repository).insert(captor.capture());
        var draft = captor.getValue();
        assertFalse(new String(draft.recipientCiphertext(), StandardCharsets.UTF_8).contains("employee@example.com"));
        assertFalse(new String(draft.payloadCiphertext(), StandardCharsets.UTF_8).contains("123456"));
        assertEquals("employee@example.com", crypto.decrypt(draft.recipientCiphertext()));
        assertTrue(crypto.decrypt(draft.payloadCiphertext()).contains("123456"));
        assertTrue(draft.idempotencyKey().startsWith(eventId.toString()));
        assertEquals("PENDING", draft.initialStatus());
    }

    @Test
    void rejectsShortEncryptionKey() {
        OutboxProperties invalid = new OutboxProperties(false, Duration.ofSeconds(1), 1, 1,
                Duration.ofMinutes(1), 2, Duration.ofMinutes(1), Duration.ofDays(1), Duration.ofDays(2),
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1), "short");
        assertThrows(IllegalStateException.class, () -> new OutboxCryptoService(invalid));
    }

    static OutboxProperties properties() {
        return new OutboxProperties(false, Duration.ofSeconds(10), 20, 2, Duration.ofMinutes(5), 6,
                Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofDays(90), Duration.ofMinutes(15),
                Duration.ofSeconds(10), Duration.ofSeconds(20), "test-outbox-encryption-key-at-least-thirty-two-characters");
    }
}
