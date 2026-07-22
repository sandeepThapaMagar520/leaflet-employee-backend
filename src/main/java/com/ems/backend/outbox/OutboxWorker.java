package com.ems.backend.outbox;

import com.ems.backend.mail.EmailDeliveryResult;
import com.ems.backend.mail.EmailService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxProperties properties;
    private final OutboxRepository repository;
    private final OutboxService outboxService;
    private final OutboxEligibilityService eligibility;
    private final EmailService emailService;
    private final RetryPolicy retryPolicy;
    private final String workerId = UUID.randomUUID().toString();
    private final ExecutorService executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public OutboxWorker(OutboxProperties properties, OutboxRepository repository, OutboxService outboxService,
                        OutboxEligibilityService eligibility, EmailService emailService, RetryPolicy retryPolicy) {
        this.properties = properties;
        this.repository = repository;
        this.outboxService = outboxService;
        this.eligibility = eligibility;
        this.emailService = emailService;
        this.retryPolicy = retryPolicy;
        this.executor = new ThreadPoolExecutor(
                properties.concurrency(), properties.concurrency(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.batchSize()),
                Thread.ofPlatform().name("outbox-delivery-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:10s}")
    public void poll() {
        if (!properties.workerEnabled() || !accepting.get()) return;
        Instant staleBefore = Instant.now().minus(properties.processingTimeout());
        long stale = repository.staleProcessingCount(staleBefore);
        if (stale > 0) log.warn("outbox_stale_processing_recovery count={}", stale);
        int expired = repository.expireDueMessages(staleBefore);
        if (expired > 0) log.info("outbox_expired count={}", expired);
        repository.claim(properties.batchSize(), workerId, staleBefore)
                .forEach(message -> executor.submit(() -> deliver(message)));
    }

    @Scheduled(fixedDelayString = "${app.outbox.metrics-interval:60s}")
    public void logQueueHealth() {
        if (!properties.workerEnabled() || !accepting.get()) return;
        log.info("outbox_queue {}", repository.queueStats());
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 37 3 * * *}")
    public void cleanup() {
        if (!properties.workerEnabled() || !accepting.get()) return;
        int count = repository.cleanup(Instant.now().minus(properties.sentRetention()),
                Instant.now().minus(properties.terminalRetention()));
        if (count > 0) log.info("outbox_cleanup count={}", count);
    }

    void deliver(OutboxMessage message) {
        Instant started = Instant.now();
        try {
            var payload = outboxService.payload(message);
            if (!eligibility.isEligible(message, payload)) {
                repository.markExpired(message, workerId, started);
                log.info("outbox_ineligible messageId={} eventId={} eventType={}", message.id(), message.eventId(), message.eventType());
                return;
            }
            EmailDeliveryResult result = emailService.deliver(
                    outboxService.recipient(message), message.templateKey(), payload, message.id());
            if (result.outcome() == EmailDeliveryResult.Outcome.ACCEPTED) {
                repository.markSent(message, workerId, result.providerMessageId(), started);
                log.info("outbox_accepted messageId={} eventId={} providerMessageId={} latencyMs={}",
                        message.id(), message.eventId(), result.providerMessageId(), java.time.Duration.between(started, Instant.now()).toMillis());
            } else {
                boolean retryable = result.outcome() == EmailDeliveryResult.Outcome.RETRYABLE_FAILURE;
                repository.markFailure(message, workerId, retryable, result.reasonCode(), result.safeSummary(),
                        retryPolicy.nextAttempt(message.attemptCount() + 1), started);
                log.warn("outbox_delivery_failure messageId={} eventId={} code={} retryable={}",
                        message.id(), message.eventId(), result.reasonCode(), retryable);
            }
        } catch (RuntimeException exception) {
            repository.markFailure(message, workerId, true, "WORKER_FAILURE", "Delivery worker failed safely",
                    retryPolicy.nextAttempt(message.attemptCount() + 1), started);
            log.warn("outbox_worker_failure messageId={} eventId={}", message.id(), message.eventId());
        }
    }

    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
