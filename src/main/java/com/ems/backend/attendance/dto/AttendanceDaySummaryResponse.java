package com.ems.backend.attendance.dto;

import com.ems.backend.attendance.AttendanceDayStatus;

import java.time.Instant;
import java.time.LocalDate;

public record AttendanceDaySummaryResponse(
        Long userId,
        String userFullName,
        LocalDate workDate,
        Instant firstStartTime,
        Instant lastEndTime,
        Instant activeSessionStartTime,
        long totalMinutes,
        long requiredMinutes,
        long graceMinutes,
        long remainingMinutes,
        long overtimeMinutes,
        long shortfallMinutes,
        AttendanceDayStatus status
) {}
