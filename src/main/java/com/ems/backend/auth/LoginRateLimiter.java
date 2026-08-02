package com.ems.backend.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final DatabaseRateLimitService rateLimitService;

    public LoginRateLimiter(DatabaseRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    public void checkAllowed(String email) {
        if (!rateLimitService.consume("login-attempt", "account", email, WINDOW, MAX_ATTEMPTS)) {
            throw new ResponseStatusException(
                    TOO_MANY_REQUESTS,
                    "Too many login attempts. Please wait 15 minutes and try again."
            );
        }
    }

    public void clear(String email) {
        rateLimitService.reset("login-attempt", "account", email);
    }
}
