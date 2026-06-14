package com.ems.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyPasswordOtpRequest(
        @Email @NotBlank String email,
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "OTP must contain exactly 6 digits")
        String otp
) {
}
