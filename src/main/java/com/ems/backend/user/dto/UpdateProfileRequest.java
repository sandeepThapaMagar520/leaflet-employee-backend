package com.ems.backend.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 255) String fullName,
        @Size(max = 50) String phone,
        @Size(max = 50) String timezone,
        @Size(max = 500) String profilePhotoUrl
) {
}
