package com.ems.backend.authorization.dto;

import jakarta.validation.constraints.NotNull;

public record AssignManagerScopeRequest(@NotNull Long managerId) {
}
