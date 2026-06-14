package com.ems.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        boolean enabled,
        String provider,
        String googleWebhookUrl,
        String googleWebhookSecret,
        String fromName,
        String frontendBaseUrl
) {
    public boolean usesGoogleAppsScript() {
        return provider != null && provider.equalsIgnoreCase("GOOGLE_APPS_SCRIPT");
    }

    public String resolvedFromName() {
        return fromName == null || fromName.isBlank() ? "Leaflet EMS" : fromName.trim();
    }
}
