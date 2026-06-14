package com.ems.backend.auth.dto;

public record OtpVerificationResponse(
        String resetToken,
        String message
) {
}
