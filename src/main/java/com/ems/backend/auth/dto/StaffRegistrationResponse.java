package com.ems.backend.auth.dto;

import com.ems.backend.user.Role;

public record StaffRegistrationResponse(
        Long userId,
        String fullName,
        String email,
        Role role,
        String message
) {
}
