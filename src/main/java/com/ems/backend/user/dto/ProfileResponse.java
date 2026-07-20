package com.ems.backend.user.dto;

import com.ems.backend.user.Role;
import com.ems.backend.user.EmploymentType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProfileResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        Boolean active,
        String profilePhotoUrl,
        UUID profileMediaAssetId,
        String profilePhotoLegacyStatus,
        String employeeId,
        LocalDate joiningDate,
        EmploymentType employmentType,
        String phone,
        String emergencyContact,
        String jobTitle,
        String department,
        String location,
        String timezone,
        Boolean emailVerified,
        Boolean mustChangePassword,
        Instant lastLoginAt,
        Instant passwordChangedAt
) {
}
