package com.ems.backend.attendance.dto;

import jakarta.validation.constraints.Size;

public record ReviewAttendanceCorrectionRequest(
        @Size(max = 1000) String reviewerNote
) {
}
