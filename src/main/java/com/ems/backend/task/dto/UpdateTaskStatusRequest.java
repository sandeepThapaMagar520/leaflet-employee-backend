package com.ems.backend.task.dto;

import com.ems.backend.task.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusRequest(
        @NotNull TaskStatus status
) {
}
