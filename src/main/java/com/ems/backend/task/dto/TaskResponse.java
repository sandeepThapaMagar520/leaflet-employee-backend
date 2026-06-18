package com.ems.backend.task.dto;

import com.ems.backend.task.TaskPriority;

import java.time.Instant;
import java.time.LocalDate;

public record TaskResponse(
        Long id,
        String title,
        String description,
        String status,
        TaskPriority priority,
        LocalDate dueDate,
        Long projectId,
        String projectName,
        Long assignedToId,
        String assignedToName,
        Long createdById,
        Instant createdAt,
        Instant updatedAt
) {
}
