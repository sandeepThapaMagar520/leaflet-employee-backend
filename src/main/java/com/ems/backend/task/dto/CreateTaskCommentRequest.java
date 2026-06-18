package com.ems.backend.task.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTaskCommentRequest(
        @NotBlank(message = "Comment is required")
        String content,
        String attachmentUrl,
        String attachmentName,
        List<Long> mentionedUserIds
) {}
