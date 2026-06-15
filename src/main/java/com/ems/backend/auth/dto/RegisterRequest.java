package com.ems.backend.auth.dto;

import com.ems.backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String fullName,
        @Email @NotBlank String email,
        @NotNull Role role,
        @NotBlank @Size(min = 8, max = 100) String temporaryPassword,
        @NotBlank @Size(max = 100) String jobTitle
) {
}
