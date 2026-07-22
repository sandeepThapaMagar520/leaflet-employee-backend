package com.ems.backend.auth.dto;

import com.ems.backend.user.Role;
import com.ems.backend.outbox.DeliveryStatus;

public record StaffRegistrationResponse(
        Long userId,
        String fullName,
        String email,
        Role role,
        DeliveryStatus notificationDeliveryStatus,
        String message
) {
}
