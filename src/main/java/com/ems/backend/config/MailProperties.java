package com.ems.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String username,
        String frontendBaseUrl
) {
    public String resolvedFromAddress() {
        if (from != null && !from.isBlank()) {
            return from.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return null;
    }
}
