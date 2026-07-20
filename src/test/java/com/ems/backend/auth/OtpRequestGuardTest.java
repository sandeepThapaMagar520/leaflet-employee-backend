package com.ems.backend.auth;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpRequestGuardTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.30", "test", "correlation");

    @Mock private DatabaseRateLimitService rateLimitService;
    @Mock private SecurityAuditService auditService;

    @Test
    void issuanceChecksCooldownAccountHourDailyAndIpHour() {
        allowAllIssuance();

        assertDoesNotThrow(() -> guard().checkIssuance("employee@example.net", REQUEST));
    }

    @Test
    void resendCooldownReturns429() {
        when(rateLimitService.consume(
                "otp-issue-cooldown", "account", "employee@example.net", Duration.ofSeconds(60), 1
        )).thenReturn(false);
        allowRemainingIssuance();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard().checkIssuance("employee@example.net", REQUEST)
        );
        assertEquals(429, exception.getStatusCode().value());
    }

    @Test
    void ipIssuanceLimitReturns429() {
        when(rateLimitService.consume(
                "otp-issue-cooldown", "account", "employee@example.net", Duration.ofSeconds(60), 1
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 5
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-day", "account", "employee@example.net", Duration.ofDays(1), 10
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "ip", REQUEST.clientIp(), Duration.ofHours(1), 20
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard().checkIssuance("employee@example.net", REQUEST)
        );
        assertEquals(429, exception.getStatusCode().value());
    }

    @Test
    void accountIssuanceLimitReturns429() {
        when(rateLimitService.consume(
                "otp-issue-cooldown", "account", "employee@example.net", Duration.ofSeconds(60), 1
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 5
        )).thenReturn(false);
        when(rateLimitService.consume(
                "otp-issue-day", "account", "employee@example.net", Duration.ofDays(1), 10
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "ip", REQUEST.clientIp(), Duration.ofHours(1), 20
        )).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard().checkIssuance("employee@example.net", REQUEST)
        );
        assertEquals(429, exception.getStatusCode().value());
    }

    private void allowAllIssuance() {
        when(rateLimitService.consume(
                "otp-issue-cooldown", "account", "employee@example.net", Duration.ofSeconds(60), 1
        )).thenReturn(true);
        allowRemainingIssuance();
    }

    private void allowRemainingIssuance() {
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 5
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-day", "account", "employee@example.net", Duration.ofDays(1), 10
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "ip", REQUEST.clientIp(), Duration.ofHours(1), 20
        )).thenReturn(true);
    }

    private OtpRequestGuard guard() {
        return new OtpRequestGuard(
                rateLimitService,
                new OtpSecurityProperties(600, 600, 60, 5, 5, 20, 10, 20, 60),
                auditService
        );
    }
}
