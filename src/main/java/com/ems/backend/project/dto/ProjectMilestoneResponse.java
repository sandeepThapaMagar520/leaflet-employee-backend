package com.ems.backend.project.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ProjectMilestoneResponse(
        Long id,
        Long projectId,
        String title,
        String description,
        LocalDate dueDate,
        boolean completed,
        Instant createdAt,
        Instant updatedAt
) {}
