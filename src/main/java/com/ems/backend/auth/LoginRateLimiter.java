package com.ems.backend.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> attemptsByEmail = new ConcurrentHashMap<>();

    public void checkAllowed(String email) {
        String key = email.toLowerCase();
        Deque<Instant> attempts = attemptsByEmail.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minus(WINDOW);
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.removeFirst();
            }
            if (attempts.size() >= MAX_ATTEMPTS) {
                throw new ResponseStatusException(
                        TOO_MANY_REQUESTS,
                        "Too many login attempts. Please wait 15 minutes and try again."
                );
            }
        }
    }

    public void recordFailure(String email) {
        String key = email.toLowerCase();
        Deque<Instant> attempts = attemptsByEmail.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(Instant.now());
        }
    }

    public void clear(String email) {
        attemptsByEmail.remove(email.toLowerCase());
    }
}
