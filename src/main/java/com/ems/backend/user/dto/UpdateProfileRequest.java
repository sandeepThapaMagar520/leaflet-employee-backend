package com.ems.backend.user.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateProfileRequest(
        @Size(max = 255) String fullName,
        @Size(max = 50) String phone,
        @Size(max = 120) String emergencyContact,
        @Size(max = 120) String location,
        @Size(max = 50) String timezone,
        UUID profileMediaAssetId
) {
}
