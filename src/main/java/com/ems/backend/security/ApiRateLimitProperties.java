package com.ems.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.api-rate-limit")
public record ApiRateLimitProperties(
        boolean enabled,
        int readRequestsPerMinute,
        int writeRequestsPerMinute,
        int expensiveReadRequestsPerMinute,
        int exportRequestsPerMinute
) {
}
