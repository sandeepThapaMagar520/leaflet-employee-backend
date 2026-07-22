package com.ems.backend.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        boolean workerEnabled,
        Duration pollInterval,
        int batchSize,
        int concurrency,
        Duration processingTimeout,
        int maxAttempts,
        Duration initialRetry,
        Duration sentRetention,
        Duration terminalRetention,
        Duration sensitiveRetention,
        Duration providerConnectTimeout,
        Duration providerReadTimeout,
        String encryptionKey
) {
    public OutboxProperties {
        pollInterval = positive(pollInterval, Duration.ofSeconds(10));
        batchSize = bounded(batchSize, 1, 100, 20);
        concurrency = bounded(concurrency, 1, 8, 2);
        processingTimeout = positive(processingTimeout, Duration.ofMinutes(5));
        maxAttempts = bounded(maxAttempts, 1, 20, 6);
        initialRetry = positive(initialRetry, Duration.ofMinutes(1));
        sentRetention = positive(sentRetention, Duration.ofDays(30));
        terminalRetention = positive(terminalRetention, Duration.ofDays(90));
        sensitiveRetention = positive(sensitiveRetention, Duration.ofHours(24));
        providerConnectTimeout = positive(providerConnectTimeout, Duration.ofSeconds(10));
        providerReadTimeout = positive(providerReadTimeout, Duration.ofSeconds(20));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
