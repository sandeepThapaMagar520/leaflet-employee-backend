package com.ems.backend.attendance;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttendanceCalculationServiceTest {
    private final AttendanceCalculationService calculator = new AttendanceCalculationService();

    @Test
    void clipsCrossMidnightSessionAndSubtractsBreakOnce() {
        AttendanceSession session = new AttendanceSession();
        session.setStartTime(Instant.parse("2026-07-19T23:30:00Z"));
        session.setEndTime(Instant.parse("2026-07-20T02:00:00Z"));
        session.setBreakMinutes(30);

        long minutes = calculator.netMinutes(
                session,
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-20T03:00:00Z")
        );

        assertEquals(90, minutes);
        assertEquals("1.50", calculator.hours(minutes).toPlainString());
    }

    @Test
    void includesOpenBreakForActiveSession() {
        AttendanceSession session = new AttendanceSession();
        session.setStartTime(Instant.parse("2026-07-20T09:00:00Z"));
        session.setBreakMinutes(10);
        session.setBreakStartedAt(Instant.parse("2026-07-20T10:00:00Z"));

        assertEquals(50, calculator.netMinutes(
                session,
                Instant.parse("2026-07-20T00:00:00Z"),
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-20T10:30:00Z")
        ));
    }
}
