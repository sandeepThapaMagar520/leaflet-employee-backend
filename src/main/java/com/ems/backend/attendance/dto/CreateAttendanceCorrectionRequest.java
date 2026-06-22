package com.ems.backend.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateAttendanceCorrectionRequest(
        @NotNull Long sessionId,
        @NotNull Instant requestedStartTime,
        @NotNull Instant requestedEndTime,
        @NotBlank @Size(max = 1000) String reason
) {
}
