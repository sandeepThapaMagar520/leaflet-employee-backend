package com.ems.backend.auth.dto;

import com.ems.backend.user.EmploymentType;
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
        @NotBlank @Size(max = 100) String jobTitle,
        @Size(max = 50) String employeeId,
        java.time.LocalDate joiningDate,
        EmploymentType employmentType,
        @Size(max = 50) String phone,
        @Size(max = 120) String emergencyContact,
        @Size(max = 100) String department,
        @Size(max = 120) String location
) {
}
