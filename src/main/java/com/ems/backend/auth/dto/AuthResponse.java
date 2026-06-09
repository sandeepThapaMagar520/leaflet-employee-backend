package com.ems.backend.auth.dto;

import com.ems.backend.user.Role;

public record AuthResponse(
        String accessToken,
        String tokenType,
        Long userId,
        String fullName,
        String email,
        Role role
) {
}
