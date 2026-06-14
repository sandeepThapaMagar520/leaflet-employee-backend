package com.ems.backend.user.dto;

import com.ems.backend.user.Role;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        Boolean active,
        String jobTitle,
        String profilePhotoUrl
) {
}
