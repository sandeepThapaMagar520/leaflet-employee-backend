package com.ems.backend.user.dto;

import com.ems.backend.user.StaffAuditAction;

import java.time.Instant;

public record StaffAuditEventResponse(
        Long id,
        StaffAuditAction action,
        String description,
        String actorName,
        Instant createdAt
) {
}
