package com.ems.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPasswordRequest(
        @NotBlank String resetToken,
        @NotBlank
        @Size(min = 8, message = "New password must be at least 8 characters")
        String newPassword
) {
}
