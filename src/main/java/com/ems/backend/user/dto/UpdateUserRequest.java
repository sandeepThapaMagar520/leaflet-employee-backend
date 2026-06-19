package com.ems.backend.user.dto;

import com.ems.backend.user.EmploymentType;
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
        @Size(max = 100) String jobTitle,
        @Size(max = 50) String employeeId,
        java.time.LocalDate joiningDate,
        @NotNull(message = "Employment type is required") EmploymentType employmentType,
        @Size(max = 50) String phone,
        @Size(max = 120) String emergencyContact,
        @Size(max = 100) String department,
        @Size(max = 120) String location,
        @Size(max = 50) String timezone
) {}
