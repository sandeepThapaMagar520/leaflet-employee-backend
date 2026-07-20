package com.ems.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.emergency-admin-bootstrap")
public record EmergencyAdminBootstrapProperties(
        boolean enabled,
        String email,
        String fullName,
        String password
) {
}

