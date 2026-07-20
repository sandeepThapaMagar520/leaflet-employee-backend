package com.ems.backend.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateLeaveStatusRequest(
        @NotBlank(message = "A review reason is required")
        @Size(max = 2000, message = "Review reason cannot exceed 2000 characters")
        String reviewerNote
) {}
