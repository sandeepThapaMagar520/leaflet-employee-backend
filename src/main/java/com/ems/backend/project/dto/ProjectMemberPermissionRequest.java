package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotNull;

public record ProjectMemberPermissionRequest(
        @NotNull Long userId,
        boolean canManageTasks,
        boolean canAddNotes
) {
}
