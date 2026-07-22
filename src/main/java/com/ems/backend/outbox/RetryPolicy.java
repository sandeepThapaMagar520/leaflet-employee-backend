package com.ems.backend.outbox;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {
    private final OutboxProperties properties;

    public RetryPolicy(OutboxProperties properties) {
        this.properties = properties;
    }

    public Instant nextAttempt(int completedAttempts) {
        long baseSeconds = switch (completedAttempts) {
            case 1 -> properties.initialRetry().toSeconds();
            case 2 -> Duration.ofMinutes(5).toSeconds();
            case 3 -> Duration.ofMinutes(15).toSeconds();
            case 4 -> Duration.ofHours(1).toSeconds();
            default -> Math.min(Duration.ofHours(6).toSeconds(), Duration.ofHours(2).toSeconds() * (1L << Math.min(4, completedAttempts - 5)));
        };
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, baseSeconds / 5 + 1));
        return Instant.now().plusSeconds(baseSeconds + jitter);
    }
}
