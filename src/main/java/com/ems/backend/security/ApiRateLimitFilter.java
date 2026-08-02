package com.ems.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_SECONDS = 60;

    private final ApiRateLimitProperties properties;
    private final ApiErrorWriter errorWriter;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(ApiRateLimitProperties properties, ApiErrorWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !properties.enabled()
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/v1/")
                || path.equals("/api/v1/health")
                || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        Limit limit = limitFor(request);
        long now = Instant.now().getEpochSecond();
        String key = authentication.getName().toLowerCase() + ":" + limit.category();
        Decision decision = consume(key, limit.maximum(), now);
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit.maximum()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetAtEpochSecond()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(
                    Math.max(decision.resetAtEpochSecond() - now, 1)
            ));
            errorWriter.write(
                    request,
                    response,
                    429,
                    "RATE_LIMITED",
                    "Too many API requests. Please wait and try again."
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Limit limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/exports/")) {
            return new Limit("export", properties.exportRequestsPerMinute());
        }
        if (path.equals("/api/v1/attendance/daily")) {
            return new Limit("expensive-read", properties.expensiveReadRequestsPerMinute());
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())) {
            return new Limit("read", properties.readRequestsPerMinute());
        }
        return new Limit("write", properties.writeRequestsPerMinute());
    }

    private Decision consume(String key, int maximum, long now) {
        long windowStart = now - Math.floorMod(now, WINDOW_SECONDS);
        Window updated = windows.compute(key, (ignored, current) -> {
            if (current == null || current.windowStartedAt() != windowStart) {
                return new Window(windowStart, 1);
            }
            if (current.count() >= maximum) {
                return new Window(windowStart, maximum + 1);
            }
            return new Window(windowStart, current.count() + 1);
        });
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().windowStartedAt() < windowStart);
        }
        boolean allowed = updated.count() <= maximum;
        int remaining = allowed ? Math.max(maximum - updated.count(), 0) : 0;
        return new Decision(allowed, remaining, windowStart + WINDOW_SECONDS);
    }

    private record Limit(String category, int maximum) {}
    private record Window(long windowStartedAt, int count) {}
    private record Decision(boolean allowed, int remaining, long resetAtEpochSecond) {}
}
