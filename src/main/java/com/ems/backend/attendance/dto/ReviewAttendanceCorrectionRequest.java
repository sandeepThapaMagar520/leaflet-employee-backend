package com.ems.backend.attendance.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record ReviewAttendanceCorrectionRequest(
        @NotBlank(message = "A review reason is required")
        @Size(max = 1000) String reviewerNote
) {
}
