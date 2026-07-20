package com.ems.backend.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class BusinessClock {
    private final Clock clock;
    private final ZoneId zoneId;

    public BusinessClock(Clock clock, @Value("${app.attendance.zone-id:Asia/Kathmandu}") String zoneId) {
        this.clock = clock;
        this.zoneId = ZoneId.of(zoneId);
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zoneId));
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zoneId).toInstant();
    }
}
