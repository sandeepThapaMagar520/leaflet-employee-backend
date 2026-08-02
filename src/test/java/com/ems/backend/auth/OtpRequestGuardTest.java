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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpRequestGuardTest {
    private static final RequestMetadata REQUEST =
            new RequestMetadata("203.0.113.30", "test", "correlation");

    @Mock private DatabaseRateLimitService rateLimitService;
    @Mock private SecurityAuditService auditService;

    @Test
    void issuanceChecksAccountMinuteHourDailyAndIpHour() {
        allowAllIssuance();

        assertDoesNotThrow(() -> guard().checkIssuance("employee@example.net", REQUEST));
    }

    @Test
    void accountMinuteLimitReturns429WithoutConsumingLongerWindows() {
        when(rateLimitService.consume(
                "otp-issue-minute", "account", "employee@example.net", Duration.ofSeconds(60), 10
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard().checkIssuance("employee@example.net", REQUEST)
        );
        assertEquals(429, exception.getStatusCode().value());
        verifyNoMoreInteractions(rateLimitService);
    }

    @Test
    void ipIssuanceLimitReturns429() {
        when(rateLimitService.consume(
                "otp-issue-minute", "account", "employee@example.net", Duration.ofSeconds(60), 10
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 30
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-day", "account", "employee@example.net", Duration.ofDays(1), 100
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "ip", REQUEST.clientIp(), Duration.ofHours(1), 100
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
                "otp-issue-minute", "account", "employee@example.net", Duration.ofSeconds(60), 10
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 30
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard().checkIssuance("employee@example.net", REQUEST)
        );
        assertEquals(429, exception.getStatusCode().value());
        verifyNoMoreInteractions(rateLimitService);
    }

    private void allowAllIssuance() {
        when(rateLimitService.consume(
                "otp-issue-minute", "account", "employee@example.net", Duration.ofSeconds(60), 10
        )).thenReturn(true);
        allowRemainingIssuance();
    }

    private void allowRemainingIssuance() {
        when(rateLimitService.consume(
                "otp-issue-hour", "account", "employee@example.net", Duration.ofHours(1), 30
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-day", "account", "employee@example.net", Duration.ofDays(1), 100
        )).thenReturn(true);
        when(rateLimitService.consume(
                "otp-issue-hour", "ip", REQUEST.clientIp(), Duration.ofHours(1), 100
        )).thenReturn(true);
    }

    private OtpRequestGuard guard() {
        return new OtpRequestGuard(
                rateLimitService,
                new OtpSecurityProperties(600, 600, 60, 5, 10, 30, 100, 100, 20, 60),
                auditService
        );
    }
}
