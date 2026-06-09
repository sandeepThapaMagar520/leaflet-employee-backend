package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectNoteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProjectNoteRequest(
        @NotBlank String content,
        @NotNull ProjectNoteType noteType
) {}
