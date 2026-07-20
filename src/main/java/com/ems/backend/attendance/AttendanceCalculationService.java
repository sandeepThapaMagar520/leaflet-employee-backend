package com.ems.backend.attendance;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

@Service
public class AttendanceCalculationService {
    public long netMinutes(AttendanceSession session, Instant windowStart, Instant windowEnd, Instant now) {
        Instant start = session.getStartTime().isBefore(windowStart) ? windowStart : session.getStartTime();
        Instant rawEnd = session.getEndTime() != null ? session.getEndTime() : now;
        Instant end = rawEnd.isAfter(windowEnd) ? windowEnd : rawEnd;
        if (!end.isAfter(start)) {
            return 0;
        }
        return Math.max(Duration.between(start, end).toMinutes() - breakMinutes(session, end), 0);
    }

    public long breakMinutes(AttendanceSession session, Instant until) {
        long saved = session.getBreakMinutes() != null ? session.getBreakMinutes() : 0;
        if (session.getBreakStartedAt() == null || until.isBefore(session.getBreakStartedAt())) {
            return saved;
        }
        return saved + Duration.between(session.getBreakStartedAt(), until).toMinutes();
    }

    public BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes / 60.0).setScale(2, RoundingMode.HALF_UP);
    }
}
