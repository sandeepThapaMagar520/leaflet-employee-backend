package com.ems.backend.settings;

import com.ems.backend.settings.dto.AppSettingsResponse;
import com.ems.backend.settings.dto.UpdateAppSettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {
    public static final String LEAVE_ANNUAL_DAYS = "leave.annual.days";
    public static final String LEAVE_SICK_DAYS = "leave.sick.days";
    public static final String LEAVE_RESET_MONTH = "leave.reset.month";
    public static final String ATTENDANCE_REQUIRED_MINUTES = "attendance.required.minutes";
    public static final String ATTENDANCE_GRACE_MINUTES = "attendance.grace.minutes";
    public static final String ATTENDANCE_BREAK_REMINDER_MINUTES = "attendance.break.reminder.minutes";
    public static final String ATTENDANCE_MISSING_CHECKOUT_MINUTES = "attendance.missing.checkout.minutes";
    public static final String ATTENDANCE_HEARTBEAT_STALE_MINUTES = "attendance.heartbeat.stale.minutes";
    public static final String ATTENDANCE_ADMIN_OVERRIDE_ENABLED = "attendance.admin.override.enabled";
    public static final String SESSION_IDLE_TIMEOUT_MINUTES = "session.idle.timeout.minutes";
    public static final String SESSION_WARNING_SECONDS = "session.warning.seconds";
    public static final String SESSION_BROWSER_ONLY = "session.browser.only";

    private final AppSettingRepository repository;

    public AppSettingsService(AppSettingRepository repository) {
        this.repository = repository;
    }

    public AppSettingsResponse getSettings() {
        return new AppSettingsResponse(
                new AppSettingsResponse.LeaveSettings(
                        getInt(LEAVE_ANNUAL_DAYS, 21),
                        getInt(LEAVE_SICK_DAYS, 12),
                        getInt(LEAVE_RESET_MONTH, 1),
                        false
                ),
                new AppSettingsResponse.AttendanceSettings(
                        getInt(ATTENDANCE_REQUIRED_MINUTES, 420),
                        getInt(ATTENDANCE_GRACE_MINUTES, 360),
                        getInt(ATTENDANCE_BREAK_REMINDER_MINUTES, 30),
                        getInt(ATTENDANCE_MISSING_CHECKOUT_MINUTES, 600),
                        getInt(ATTENDANCE_HEARTBEAT_STALE_MINUTES, 10),
                        getBoolean(ATTENDANCE_ADMIN_OVERRIDE_ENABLED, true)
                ),
                new AppSettingsResponse.SessionSettings(
                        getInt(SESSION_IDLE_TIMEOUT_MINUTES, 60),
                        getInt(SESSION_WARNING_SECONDS, 60),
                        true
                )
        );
    }

    @Transactional
    public AppSettingsResponse updateSettings(UpdateAppSettingsRequest request) {
        setInt(LEAVE_ANNUAL_DAYS, request.leave().annualLeaveDays());
        setInt(LEAVE_SICK_DAYS, request.leave().sickLeaveDays());
        setInt(LEAVE_RESET_MONTH, request.leave().resetMonth());
        setInt(ATTENDANCE_REQUIRED_MINUTES, request.attendance().requiredMinutes());
        setInt(ATTENDANCE_GRACE_MINUTES, request.attendance().graceMinutes());
        setInt(ATTENDANCE_BREAK_REMINDER_MINUTES, request.attendance().breakReminderMinutes());
        setInt(ATTENDANCE_MISSING_CHECKOUT_MINUTES, request.attendance().missingCheckoutMinutes());
        setInt(ATTENDANCE_HEARTBEAT_STALE_MINUTES, request.attendance().heartbeatStaleMinutes());
        setBoolean(ATTENDANCE_ADMIN_OVERRIDE_ENABLED, request.attendance().adminOverrideEnabled());
        setInt(SESSION_IDLE_TIMEOUT_MINUTES, request.session().idleTimeoutMinutes());
        setInt(SESSION_WARNING_SECONDS, request.session().warningSeconds());
        setBoolean(SESSION_BROWSER_ONLY, true);
        return getSettings();
    }

    public int annualLeaveDays() {
        return getInt(LEAVE_ANNUAL_DAYS, 21);
    }

    public int sickLeaveDays() {
        return getInt(LEAVE_SICK_DAYS, 12);
    }

    public int leaveResetMonth() {
        return Math.min(Math.max(getInt(LEAVE_RESET_MONTH, 1), 1), 12);
    }

    public int attendanceRequiredMinutes() {
        return getInt(ATTENDANCE_REQUIRED_MINUTES, 420);
    }

    public int attendanceGraceMinutes() {
        return getInt(ATTENDANCE_GRACE_MINUTES, 360);
    }

    public int attendanceMissingCheckoutMinutes() {
        return getInt(ATTENDANCE_MISSING_CHECKOUT_MINUTES, 600);
    }

    public int attendanceHeartbeatStaleMinutes() {
        return getInt(ATTENDANCE_HEARTBEAT_STALE_MINUTES, 10);
    }

    public boolean attendanceAdminOverrideEnabled() {
        return getBoolean(ATTENDANCE_ADMIN_OVERRIDE_ENABLED, true);
    }

    private int getInt(String key, int fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(value -> parseInt(value, fallback))
                .orElse(fallback);
    }

    private boolean getBoolean(String key, boolean fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(Boolean::parseBoolean)
                .orElse(fallback);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void setInt(String key, int value) {
        setValue(key, String.valueOf(value));
    }

    private void setBoolean(String key, boolean value) {
        setValue(key, String.valueOf(value));
    }

    private void setValue(String key, String value) {
        AppSetting setting = repository.findById(key).orElseGet(() -> {
            AppSetting next = new AppSetting();
            next.setKey(key);
            return next;
        });
        setting.setValue(value);
        repository.save(setting);
    }
}
