package com.ems.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.authentication-cache")
public record AuthenticationCacheProperties(
        boolean enabled,
        long ttlSeconds,
        int maximumEntries
) {
}
