package com.ems.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StartAccountSetupRequest(
        @Email @NotBlank String email,
        @NotBlank String temporaryPassword
) {
}
