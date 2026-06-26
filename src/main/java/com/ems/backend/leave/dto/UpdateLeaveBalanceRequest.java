package com.ems.backend.leave.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateLeaveBalanceRequest(
        @NotNull(message = "Remaining leave days is required")
        @Min(value = 0, message = "Remaining leave days cannot be negative")
        Integer remainingDays
) {
}
