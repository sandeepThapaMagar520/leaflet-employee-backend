package com.ems.backend.task.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskCommentResponse(
        Long id,
        Long taskId,
        Long userId,
        String userFullName,
        String content,
        UUID mediaAssetId,
        String downloadUrl,
        String attachmentName,
        String legacyAssetStatus,
        Instant createdAt
) {}
