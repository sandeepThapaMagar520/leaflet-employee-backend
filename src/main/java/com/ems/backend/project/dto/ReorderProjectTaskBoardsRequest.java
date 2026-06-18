package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderProjectTaskBoardsRequest(
        @NotEmpty List<Long> boardIds
) {
}
