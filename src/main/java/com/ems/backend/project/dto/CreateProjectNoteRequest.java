package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectNoteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateProjectNoteRequest(
        @NotBlank String content,
        @NotNull ProjectNoteType noteType,
        @Size(max = 10) List<UUID> mediaAssetIds
) {
    public CreateProjectNoteRequest {
        mediaAssetIds = mediaAssetIds == null ? List.of() : List.copyOf(mediaAssetIds);
    }
}
