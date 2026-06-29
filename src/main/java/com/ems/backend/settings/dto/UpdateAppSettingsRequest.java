package com.ems.backend.settings.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateAppSettingsRequest(
        @Valid @NotNull LeaveSettings leave,
        @Valid @NotNull AttendanceSettings attendance,
        @Valid @NotNull SessionSettings session
) {
    public record LeaveSettings(
            @Min(0) @Max(365) int annualLeaveDays,
            @Min(0) @Max(365) int sickLeaveDays,
            @Min(1) @Max(12) int resetMonth
    ) {}

    public record AttendanceSettings(
            @Min(1) @Max(1440) int requiredMinutes,
            @Min(0) @Max(1440) int graceMinutes,
            @Min(1) @Max(240) int breakReminderMinutes,
            @Min(60) @Max(1440) int missingCheckoutMinutes,
            @Min(1) @Max(120) int heartbeatStaleMinutes,
            boolean adminOverrideEnabled
    ) {}

    public record SessionSettings(
            @Min(5) @Max(1440) int idleTimeoutMinutes,
            @Min(10) @Max(600) int warningSeconds
    ) {}
}
