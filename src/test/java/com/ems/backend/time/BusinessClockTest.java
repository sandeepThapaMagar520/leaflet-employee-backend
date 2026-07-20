package com.ems.backend.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessClockTest {
    @Test
    void derivesBusinessDateFromInjectedClockAndZone() {
        Clock fixed = Clock.fixed(Instant.parse("2026-07-20T20:00:00Z"), ZoneOffset.UTC);
        BusinessClock clock = new BusinessClock(fixed, "Asia/Kathmandu");

        assertEquals(Instant.parse("2026-07-20T20:00:00Z"), clock.now());
        assertEquals("2026-07-21", clock.today().toString());
        assertEquals(
                Instant.parse("2026-07-20T18:15:00Z"),
                clock.startOfDay(clock.today())
        );
    }
}
