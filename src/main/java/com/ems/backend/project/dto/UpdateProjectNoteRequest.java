package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectNoteType;
import jakarta.validation.constraints.NotBlank;

public record UpdateProjectNoteRequest(
        @NotBlank String content,
        ProjectNoteType noteType
) {}
