package com.ems.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailChange(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "OTP must contain exactly 6 digits")
        String otp
) {
}
