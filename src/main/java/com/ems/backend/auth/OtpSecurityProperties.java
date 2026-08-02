package com.ems.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.otp")
public record OtpSecurityProperties(
        long validitySeconds,
        long resetTokenValiditySeconds,
        long resendCooldownSeconds,
        int maximumVerificationAttempts,
        int accountIssuanceLimitPerMinute,
        int accountIssuanceLimitPerHour,
        int ipIssuanceLimitPerHour,
        int accountIssuanceLimitPerDay,
        int accountVerificationLimitPerFifteenMinutes,
        int ipVerificationLimitPerFifteenMinutes
) {
}
