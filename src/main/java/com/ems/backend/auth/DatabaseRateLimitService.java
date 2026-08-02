package com.ems.backend.auth;

import com.ems.backend.security.TokenHashingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Service
public class DatabaseRateLimitService {
    private final JdbcTemplate jdbcTemplate;
    private final TokenHashingService tokenHashingService;

    public DatabaseRateLimitService(JdbcTemplate jdbcTemplate, TokenHashingService tokenHashingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenHashingService = tokenHashingService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String action, String dimension, String rawKey, Duration window, int maximum) {
        String rateKey = rateKey(action, dimension, rawKey);
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        return !jdbcTemplate.queryForList("""
                INSERT INTO auth_rate_limit_buckets (
                    rate_key, window_started_at, event_count, updated_at
                ) VALUES (?, ?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    window_started_at = CASE
                        WHEN auth_rate_limit_buckets.window_started_at <= ?
                            THEN EXCLUDED.window_started_at
                        ELSE auth_rate_limit_buckets.window_started_at
                    END,
                    event_count = CASE
                        WHEN auth_rate_limit_buckets.window_started_at <= ? THEN 1
                        ELSE auth_rate_limit_buckets.event_count + 1
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE auth_rate_limit_buckets.window_started_at <= ?
                   OR auth_rate_limit_buckets.event_count < ?
                RETURNING event_count
                """, Integer.class, rateKey, nowTimestamp, cutoffTimestamp, cutoffTimestamp,
                cutoffTimestamp, maximum).isEmpty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String action, String dimension, String rawKey) {
        jdbcTemplate.update(
                "DELETE FROM auth_rate_limit_buckets WHERE rate_key = ?",
                rateKey(action, dimension, rawKey)
        );
    }

    private String rateKey(String action, String dimension, String rawKey) {
        String safeRawKey = rawKey == null || rawKey.isBlank() ? "unknown" : rawKey.trim().toLowerCase();
        return action + ":" + dimension + ":" + tokenHashingService.sha256(safeRawKey);
    }
}
