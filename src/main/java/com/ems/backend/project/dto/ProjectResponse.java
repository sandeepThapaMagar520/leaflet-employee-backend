package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate,
        Long managerId,
        String managerName,
        Long createdById,
        List<ProjectEmployeeResponse> assignedEmployees,
        String clientNotes,
        String internalNotes,
        UUID documentMediaAssetId,
        String documentDownloadUrl,
        String documentLegacyStatus,
        BigDecimal budgetAmount,
        BigDecimal totalPaid,
        BigDecimal lastPaymentAmount,
        Instant lastPaymentAt,
        String lastPaymentNote,
        boolean canManageProject,
        boolean canViewFinancials,
        boolean canRecordPayment,
        Integer progressPercentage,
        Instant createdAt,
        Instant updatedAt
) {
    public record ProjectEmployeeResponse(
            Long id,
            String fullName,
            boolean canManageTasks,
            boolean canAddNotes
    ) {}
}
