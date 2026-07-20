package com.ems.backend.attendance.dto;

import com.ems.backend.attendance.AttendanceCorrectionStatus;

import java.time.Instant;

public record AttendanceCorrectionResponse(
        Long id,
        Long sessionId,
        Long userId,
        String userFullName,
        Instant originalStartTime,
        Instant originalEndTime,
        Instant requestedStartTime,
        Instant requestedEndTime,
        String reason,
        AttendanceCorrectionStatus status,
        String reviewerFullName,
        String reviewerNote,
        Instant reviewedAt,
        Instant createdAt,
        boolean canReview
) {
}
