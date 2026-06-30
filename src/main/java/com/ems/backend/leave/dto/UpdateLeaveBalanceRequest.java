package com.ems.backend.leave.dto;

import jakarta.validation.constraints.Min;

public record UpdateLeaveBalanceRequest(
        @Min(value = 0, message = "Remaining leave days cannot be negative")
        Integer remainingDays,
        @Min(value = 0, message = "Annual remaining leave days cannot be negative")
        Integer annualRemainingDays,
        @Min(value = 0, message = "Sick remaining leave days cannot be negative")
        Integer sickRemainingDays
) {
}
