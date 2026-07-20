package com.ems.backend.authorization.dto;

import java.time.Instant;

public record ManagerScopeResponse(
        Long id,
        Long managerId,
        String managerName,
        Long employeeId,
        String employeeName,
        boolean active,
        Instant assignedAt,
        Instant endedAt,
        long version
) {
}
