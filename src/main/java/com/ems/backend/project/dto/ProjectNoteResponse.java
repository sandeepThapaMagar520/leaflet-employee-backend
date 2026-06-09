package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectNoteType;
import java.time.Instant;

public record ProjectNoteResponse(
        Long id,
        String content,
        ProjectNoteType noteType,
        String createdByName,
        Instant createdAt
) {}
