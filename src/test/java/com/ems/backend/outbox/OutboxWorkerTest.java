package com.ems.backend.outbox;

import com.ems.backend.mail.EmailDeliveryResult;
import com.ems.backend.mail.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxWorkerTest {
    private OutboxWorker worker;

    @AfterEach
    void stopWorker() { if (worker != null) worker.shutdown(); }

    @Test
    void acceptedDeliveryStoresProviderIdentity() {
        Fixture fixture = fixture();
        when(fixture.provider.deliver(anyString(), anyString(), anyMap(), any())).thenReturn(
                EmailDeliveryResult.accepted("provider-id"));
        worker.deliver(fixture.message);
        verify(fixture.repository).markSent(eq(fixture.message), anyString(), eq("provider-id"), any());
        verify(fixture.repository, never()).markFailure(any(), anyString(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void retryableAndPermanentFailuresUseDifferentPersistencePaths() {
        Fixture retryable = fixture();
        when(retryable.provider.deliver(anyString(), anyString(), anyMap(), any())).thenReturn(
                EmailDeliveryResult.retryable("PROVIDER_TIMEOUT", "Timed out"));
        worker.deliver(retryable.message);
        verify(retryable.repository).markFailure(eq(retryable.message), anyString(), eq(true),
                eq("PROVIDER_TIMEOUT"), anyString(), any(), any());
        worker.shutdown();

        Fixture permanent = fixture();
        when(permanent.provider.deliver(anyString(), anyString(), anyMap(), any())).thenReturn(
                EmailDeliveryResult.permanent("INVALID_RECIPIENT", "Rejected"));
        worker.deliver(permanent.message);
        verify(permanent.repository).markFailure(eq(permanent.message), anyString(), eq(false),
                eq("INVALID_RECIPIENT"), anyString(), any(), any());
    }

    @Test
    void obsoleteMessageIsExpiredWithoutProviderCall() {
        Fixture fixture = fixture();
        when(fixture.eligibility.isEligible(any(), anyMap())).thenReturn(false);
        worker.deliver(fixture.message);
        verify(fixture.repository).markExpired(eq(fixture.message), anyString(), any());
        verifyNoInteractions(fixture.provider);
    }

    @Test
    void shutdownStopsNewClaims() {
        OutboxProperties base = OutboxServiceTest.properties();
        OutboxProperties enabled = new OutboxProperties(true, base.pollInterval(), base.batchSize(), base.concurrency(),
                base.processingTimeout(), base.maxAttempts(), base.initialRetry(), base.sentRetention(),
                base.terminalRetention(), base.sensitiveRetention(), base.providerConnectTimeout(),
                base.providerReadTimeout(), base.encryptionKey());
        OutboxRepository repository = mock(OutboxRepository.class);
        worker = new OutboxWorker(enabled, repository, mock(OutboxService.class),
                mock(OutboxEligibilityService.class), mock(EmailService.class), mock(RetryPolicy.class));
        worker.shutdown();
        worker.poll();
        verifyNoInteractions(repository);
    }

    private Fixture fixture() {
        OutboxProperties properties = OutboxServiceTest.properties();
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxService service = mock(OutboxService.class);
        OutboxEligibilityService eligibility = mock(OutboxEligibilityService.class);
        EmailService provider = mock(EmailService.class);
        RetryPolicy retry = mock(RetryPolicy.class);
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), "TASK_ASSIGNED",
                7L, new byte[]{1}, "hash", "IN_APP_NOTIFICATION", new byte[]{2}, false,
                null, 0, 6, "correlation");
        when(service.payload(message)).thenReturn(Map.of("title", "Task"));
        when(service.recipient(message)).thenReturn("employee@example.com");
        when(eligibility.isEligible(eq(message), anyMap())).thenReturn(true);
        when(retry.nextAttempt(anyInt())).thenReturn(Instant.now().plusSeconds(60));
        worker = new OutboxWorker(properties, repository, service, eligibility, provider, retry);
        return new Fixture(repository, eligibility, provider, message);
    }

    private record Fixture(OutboxRepository repository, OutboxEligibilityService eligibility,
                           EmailService provider, OutboxMessage message) {}
}
