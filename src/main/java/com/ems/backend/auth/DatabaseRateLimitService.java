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
        String safeRawKey = rawKey == null || rawKey.isBlank() ? "unknown" : rawKey.trim().toLowerCase();
        String rateKey = action + ":" + dimension + ":" + tokenHashingService.sha256(safeRawKey);
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                preparedStatement -> preparedStatement.setString(1, rateKey),
                resultSet -> null
        );
        jdbcTemplate.update(
                "DELETE FROM auth_rate_limit_events WHERE rate_key = ? AND attempted_at <= ?",
                rateKey,
                cutoffTimestamp
        );
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_rate_limit_events WHERE rate_key = ?",
                Integer.class,
                rateKey
        );
        if (count != null && count >= maximum) {
            return false;
        }
        jdbcTemplate.update(
                "INSERT INTO auth_rate_limit_events (rate_key, attempted_at) VALUES (?, ?)",
                rateKey,
                nowTimestamp
        );
        return true;
    }
}
