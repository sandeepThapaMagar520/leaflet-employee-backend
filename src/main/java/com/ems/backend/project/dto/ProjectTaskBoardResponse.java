package com.ems.backend.project.dto;

public record ProjectTaskBoardResponse(
        Long id,
        Long projectId,
        String statusKey,
        String name,
        int displayOrder,
        boolean defaultBoard,
        boolean terminal
) {
}
