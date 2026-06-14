package com.ems.backend.user.dto;

import com.ems.backend.user.Role;

import java.time.Instant;

public record ProfileResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        Boolean active,
        String profilePhotoUrl,
        String phone,
        String jobTitle,
        String department,
        String timezone,
        Boolean emailVerified,
        Boolean mustChangePassword,
        Instant lastLoginAt,
        Instant passwordChangedAt
) {
}
