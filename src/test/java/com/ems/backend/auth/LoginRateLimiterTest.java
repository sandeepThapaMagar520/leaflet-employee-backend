package com.ems.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginRateLimiterTest {
    @Test
    void blocksAfterTooManyFailures() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String email = "user@example.com";

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(email);
        }

        assertThrows(ResponseStatusException.class, () -> limiter.checkAllowed(email));
    }

    @Test
    void clearsFailuresAfterSuccessfulLogin() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String email = "user@example.com";

        limiter.recordFailure(email);
        limiter.recordFailure(email);
        limiter.clear(email);

        assertDoesNotThrow(() -> limiter.checkAllowed(email));
    }
}
