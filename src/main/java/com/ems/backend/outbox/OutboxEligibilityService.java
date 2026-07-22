package com.ems.backend.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class OutboxEligibilityService {
    private final JdbcTemplate jdbc;

    public OutboxEligibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEligible(OutboxMessage message, Map<String, Object> payload) {
        if (message.expiresAt() != null && !message.expiresAt().isAfter(java.time.Instant.now())) return false;
        if (message.recipientUserId() == null) return true;
        String predicate = switch (message.eventType()) {
            case "PASSWORD_RECOVERY_OTP" -> "active AND password_otp_hash IS NOT NULL AND password_otp_expires_at > CURRENT_TIMESTAMP AND password_otp_purpose='PASSWORD_RECOVERY'";
            case "ACCOUNT_SETUP_OTP" -> "active AND must_change_password AND password_otp_hash IS NOT NULL AND password_otp_expires_at > CURRENT_TIMESTAMP AND password_otp_purpose='ACCOUNT_SETUP'";
            case "ACCOUNT_SETUP" -> "active AND must_change_password";
            case "EMAIL_CHANGE_OTP" -> "active AND pending_email IS NOT NULL AND email_change_otp_hash IS NOT NULL AND email_change_otp_expires_at > CURRENT_TIMESTAMP";
            case "EMAIL_VERIFICATION" -> "active AND NOT email_verified AND email_verification_token_hash IS NOT NULL AND email_verification_expires_at > CURRENT_TIMESTAMP";
            default -> "active";
        };
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE id=? AND " + predicate,
                Integer.class, message.recipientUserId());
        if (count == null || count != 1) return false;
        Long resourceId = longValue(payload.get("resourceId"));
        if (resourceId == null) return true;
        return switch (message.eventType()) {
            case "LEAVE_REVIEWED" -> matches("SELECT count(*) FROM leave_requests WHERE id=? AND status=?",
                    resourceId, payload.get("expectedStatus"));
            case "TASK_ASSIGNED", "TASK_REASSIGNED" -> matches(
                    "SELECT count(*) FROM tasks WHERE id=? AND assigned_to_id=?", resourceId, message.recipientUserId());
            case "PROJECT_ASSIGNED" -> matches(
                    "SELECT count(*) FROM project_assignments WHERE project_id=? AND user_id=?", resourceId, message.recipientUserId());
            default -> true;
        };
    }

    private boolean matches(String sql, Object first, Object second) {
        Integer count = jdbc.queryForObject(sql, Integer.class, first, second);
        return count != null && count == 1;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
