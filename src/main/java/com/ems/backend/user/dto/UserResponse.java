package com.ems.backend.user.dto;

import com.ems.backend.user.Role;
import com.ems.backend.user.AccountStatus;
import com.ems.backend.user.EmploymentType;

import java.time.Instant;
import java.time.LocalDate;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        Boolean active,
        String jobTitle,
        String profilePhotoUrl,
        String employeeId,
        LocalDate joiningDate,
        EmploymentType employmentType,
        String phone,
        String emergencyContact,
        String department,
        String location,
        String timezone,
        AccountStatus accountStatus,
        Boolean emailVerified,
        Boolean mustChangePassword,
        Instant lastLoginAt
) {
}
