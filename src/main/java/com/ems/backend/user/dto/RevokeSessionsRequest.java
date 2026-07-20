package com.ems.backend.user.dto;

import jakarta.validation.constraints.Size;

public record RevokeSessionsRequest(
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {
}
