package com.ems.backend.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttendanceOverrideRequest(
        @NotBlank(message = "An override reason is required")
        @Size(max = 1000, message = "Override reason cannot exceed 1000 characters")
        String reason
) {}
