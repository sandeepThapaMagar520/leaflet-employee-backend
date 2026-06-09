package com.ems.backend.leave.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateLeaveStatusRequest(
        @NotBlank(message = "Reviewer note is required")
        String reviewerNote
) {}
