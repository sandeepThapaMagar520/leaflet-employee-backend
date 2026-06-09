package com.ems.backend.task.dto;

import java.time.Instant;

public record TaskCommentResponse(
        Long id,
        Long taskId,
        Long userId,
        String userFullName,
        String content,
        String attachmentUrl,
        String attachmentName,
        Instant createdAt
) {}
