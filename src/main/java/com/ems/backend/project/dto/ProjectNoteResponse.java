package com.ems.backend.project.dto;

import com.ems.backend.project.ProjectNoteType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectNoteResponse(
        Long id,
        String content,
        ProjectNoteType noteType,
        String createdByName,
        Instant createdAt,
        List<Attachment> attachments,
        String legacyAttachmentStatus
) {
    public record Attachment(
            UUID mediaAssetId,
            String fileName,
            String contentType,
            long sizeBytes,
            String downloadUrl
    ) {}
}
