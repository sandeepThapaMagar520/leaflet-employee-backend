package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateProjectMilestoneRequest(
        @NotBlank(message = "Milestone title is required")
        String title,
        String description,
        LocalDate dueDate
) {}
