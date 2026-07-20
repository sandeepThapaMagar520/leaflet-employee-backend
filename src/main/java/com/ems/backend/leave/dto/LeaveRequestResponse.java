package com.ems.backend.leave.dto;

import com.ems.backend.leave.LeaveStatus;
import com.ems.backend.leave.LeaveType;

import java.time.Instant;
import java.time.LocalDate;

public record LeaveRequestResponse(
        Long id,
        Long userId,
        String userFullName,
        LeaveType leaveType,
        LeaveStatus status,
        LocalDate startDate,
        LocalDate endDate,
        int requestedDays,
        String reason,
        String reviewerName,
        String reviewerNote,
        Instant reviewedAt,
        Instant createdAt,
        boolean canReview
) {}
