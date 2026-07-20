package com.ems.backend.user.dto;

import com.ems.backend.user.Role;

public record ManagerDirectoryResponse(
        Long id,
        String employeeId,
        String fullName,
        String email,
        Role role,
        String jobTitle,
        String department,
        Boolean active,
        String profilePhotoUrl
) {
}
