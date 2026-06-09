package com.ems.backend.attendance.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AttendanceSessionResponse(
        Long id,
        Long userId,
        String userFullName,
        Instant startTime,
        Instant endTime,
        BigDecimal totalHours
) {}
