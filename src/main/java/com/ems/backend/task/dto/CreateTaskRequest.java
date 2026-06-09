package com.ems.backend.task.dto;

import com.ems.backend.task.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateTaskRequest(
        @NotBlank String title,
        String description,
        @NotNull TaskPriority priority,
        LocalDate dueDate,
        @NotNull Long projectId,
        @NotNull Long assignedToId
) {
}
