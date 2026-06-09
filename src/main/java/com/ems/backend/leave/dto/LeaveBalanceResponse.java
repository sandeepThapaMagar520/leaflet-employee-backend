package com.ems.backend.leave.dto;

public record LeaveBalanceResponse(
        int annualAllowance,
        int approvedDays,
        int remainingDays
) {}
