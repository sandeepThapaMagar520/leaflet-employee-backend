package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectTaskBoardRequest(
        @NotBlank @Size(max = 80) String name
) {
}
