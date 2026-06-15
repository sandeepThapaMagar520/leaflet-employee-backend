package com.ems.backend.user.dto;

import com.ems.backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank(message = "Full name is required") String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Invalid email format") String email,
        @NotNull(message = "Role is required") Role role,
        @NotNull(message = "Active status is required") Boolean active,
        @Size(max = 100) String jobTitle
) {}
