package com.ems.backend.leave.dto;

import com.ems.backend.leave.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateLeaveRequest(
        @NotNull(message = "Leave type is required")
        LeaveType leaveType,
        @NotNull(message = "Start date is required")
        LocalDate startDate,
        @NotNull(message = "End date is required")
        LocalDate endDate,
        @NotBlank(message = "Reason is required")
        String reason
) {}
