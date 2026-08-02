package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
public class OtpRequestGuard {
    private final DatabaseRateLimitService rateLimitService;
    private final OtpSecurityProperties properties;
    private final SecurityAuditService auditService;

    public OtpRequestGuard(
            DatabaseRateLimitService rateLimitService,
            OtpSecurityProperties properties,
            SecurityAuditService auditService
    ) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.auditService = auditService;
    }

    public void checkIssuance(String normalizedEmail, RequestMetadata metadata) {
        boolean allowed = rateLimitService.consume(
                "otp-issue-minute", "account", normalizedEmail,
                Duration.ofSeconds(properties.resendCooldownSeconds()),
                properties.accountIssuanceLimitPerMinute()
        );
        if (!allowed) {
            rejectIssuance(normalizedEmail, metadata);
        }
        allowed = rateLimitService.consume(
                "otp-issue-hour", "account", normalizedEmail,
                Duration.ofHours(1), properties.accountIssuanceLimitPerHour()
        );
        if (!allowed) {
            rejectIssuance(normalizedEmail, metadata);
        }
        allowed = rateLimitService.consume(
                "otp-issue-day", "account", normalizedEmail,
                Duration.ofDays(1), properties.accountIssuanceLimitPerDay()
        );
        if (!allowed) {
            rejectIssuance(normalizedEmail, metadata);
        }
        allowed = rateLimitService.consume(
                "otp-issue-hour", "ip", metadata.clientIp(),
                Duration.ofHours(1), properties.ipIssuanceLimitPerHour()
        );
        if (!allowed) {
            rejectIssuance(normalizedEmail, metadata);
        }
    }

    private void rejectIssuance(String normalizedEmail, RequestMetadata metadata) {
        auditService.recordBestEffort(
                null, "OTP_ISSUANCE_THROTTLED", "RATE_LIMITED", normalizedEmail, metadata
        );
        throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many verification-code requests. Please wait and try again."
        );
    }

    public void checkVerification(String normalizedEmail, RequestMetadata metadata) {
        boolean allowed = rateLimitService.consume(
                "otp-verify", "account", normalizedEmail,
                Duration.ofMinutes(15), properties.accountVerificationLimitPerFifteenMinutes()
        );
        allowed &= rateLimitService.consume(
                "otp-verify", "ip", metadata.clientIp(),
                Duration.ofMinutes(15), properties.ipVerificationLimitPerFifteenMinutes()
        );
        if (!allowed) {
            auditService.recordBestEffort(
                    null, "OTP_VERIFICATION_THROTTLED", "RATE_LIMITED", normalizedEmail, metadata
            );
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many verification attempts. Please wait and try again."
            );
        }
    }
}
