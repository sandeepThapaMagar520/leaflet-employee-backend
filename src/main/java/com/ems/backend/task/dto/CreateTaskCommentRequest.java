package com.ems.backend.task.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record CreateTaskCommentRequest(
        @NotBlank(message = "Comment is required")
        String content,
        UUID mediaAssetId,
        List<Long> mentionedUserIds
) {}
