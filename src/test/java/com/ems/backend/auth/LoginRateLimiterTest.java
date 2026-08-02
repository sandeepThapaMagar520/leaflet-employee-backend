package com.ems.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginRateLimiterTest {
    @Test
    void blocksWhenDurableLimitIsExhausted() {
        DatabaseRateLimitService rateLimitService = mock(DatabaseRateLimitService.class);
        when(rateLimitService.consume(eq("login-attempt"), eq("account"), any(), any(), anyInt()))
                .thenReturn(false);
        LoginRateLimiter limiter = new LoginRateLimiter(rateLimitService);
        String email = "user@example.com";

        assertThrows(ResponseStatusException.class, () -> limiter.checkAllowed(email));
    }

    @Test
    void clearsDurableCounterAfterSuccessfulLogin() {
        DatabaseRateLimitService rateLimitService = mock(DatabaseRateLimitService.class);
        when(rateLimitService.consume(eq("login-attempt"), eq("account"), any(), any(), anyInt()))
                .thenReturn(true);
        LoginRateLimiter limiter = new LoginRateLimiter(rateLimitService);
        String email = "user@example.com";

        assertDoesNotThrow(() -> limiter.checkAllowed(email));
        limiter.clear(email);

        verify(rateLimitService).reset("login-attempt", "account", email);
    }
}
