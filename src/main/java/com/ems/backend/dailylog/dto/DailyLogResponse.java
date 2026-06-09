package com.ems.backend.dailylog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record DailyLogResponse(
        Long id,
        Long userId,
        String userFullName,
        LocalDate logDate,
        String summary,
        String problemsFaced,
        Instant createdAt
) {}
