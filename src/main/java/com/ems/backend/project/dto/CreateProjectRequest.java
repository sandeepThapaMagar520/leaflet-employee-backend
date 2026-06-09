package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateProjectRequest(
        @NotBlank String name,
        String description,
        LocalDate startDate,
        LocalDate dueDate,
        @NotNull Long managerId,
        List<Long> assignedEmployeeIds,
        String clientNotes,
        String documentUrl,
        BigDecimal budgetAmount,
        String internalNotes
) {
}
