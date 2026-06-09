package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
        String documentUrl,
        BigDecimal budgetAmount,
        BigDecimal totalPaid,
        BigDecimal lastPaymentAmount,
        Instant lastPaymentAt,
        String lastPaymentNote,
        Integer progressPercentage,
        Instant createdAt,
        Instant updatedAt
) {
    public record ProjectEmployeeResponse(Long id, String fullName) {}
}
