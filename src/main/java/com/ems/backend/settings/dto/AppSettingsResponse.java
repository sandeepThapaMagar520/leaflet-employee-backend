package com.ems.backend.settings.dto;

public record AppSettingsResponse(
        LeaveSettings leave,
        AttendanceSettings attendance,
        SessionSettings session
) {
    public record LeaveSettings(
            int annualLeaveDays,
            int sickLeaveDays,
            int resetMonth,
            boolean carryForwardAllowed
    ) {}

    public record AttendanceSettings(
            int requiredMinutes,
            int graceMinutes,
            int breakReminderMinutes,
            int missingCheckoutMinutes,
            int heartbeatStaleMinutes,
            boolean adminOverrideEnabled
    ) {}

    public record SessionSettings(
            int idleTimeoutMinutes,
            int warningSeconds,
            boolean browserSessionOnly
    ) {}
}
