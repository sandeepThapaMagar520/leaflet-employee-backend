package com.ems.backend.auth.dto;

import com.ems.backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterRequest(
        @NotBlank String fullName,
        @Email @NotBlank String email,
        @NotNull Role role
) {
}
