package com.ems.backend.task.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateTaskStatusRequest(
        @NotBlank String status
) {
}
