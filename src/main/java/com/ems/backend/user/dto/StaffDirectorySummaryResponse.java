package com.ems.backend.user.dto;

import java.util.List;

public record StaffDirectorySummaryResponse(
        long totalStaff,
        long activeStaff,
        long managers,
        long onboardingPending,
        long incompleteRecords,
        List<String> departments
) {
}
