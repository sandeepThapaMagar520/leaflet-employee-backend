package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateProjectRequest(
        @NotBlank String name,
        String description,
        @NotNull ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate,
        @NotNull Long managerId,
        List<Long> assignedEmployeeIds,
        List<ProjectMemberPermissionRequest> memberPermissions,
        String clientNotes,
        UUID documentMediaAssetId,
        BigDecimal budgetAmount,
        String internalNotes
) {
}
