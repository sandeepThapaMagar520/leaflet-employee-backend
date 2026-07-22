package com.ems.backend.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {
    @Test
    void backoffUsesConfiguredScheduleWithBoundedPositiveJitter() {
        RetryPolicy policy = new RetryPolicy(OutboxServiceTest.properties());
        assertWindow(policy, 1, Duration.ofMinutes(1));
        assertWindow(policy, 2, Duration.ofMinutes(5));
        assertWindow(policy, 3, Duration.ofMinutes(15));
        assertWindow(policy, 4, Duration.ofHours(1));
    }

    private void assertWindow(RetryPolicy policy, int attempt, Duration base) {
        Instant before = Instant.now();
        Instant next = policy.nextAttempt(attempt);
        assertTrue(!next.isBefore(before.plus(base)));
        assertTrue(next.isBefore(before.plus(base).plusSeconds(base.toSeconds() / 5 + 2)));
    }
}
