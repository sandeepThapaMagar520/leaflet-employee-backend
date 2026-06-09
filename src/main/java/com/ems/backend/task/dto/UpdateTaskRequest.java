package com.ems.backend.task.dto;

import com.ems.backend.task.TaskPriority;
import com.ems.backend.task.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record UpdateTaskRequest(
        @NotBlank String title,
        String description,
        @NotNull TaskStatus status,
        @NotNull TaskPriority priority,
        LocalDate dueDate,
        @NotNull Long assignedToId
) {
}
