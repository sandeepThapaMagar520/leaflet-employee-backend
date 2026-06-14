package com.ems.backend.user.dto;

public record NotificationPreferencesResponse(
        boolean emailTaskAssigned,
        boolean emailTaskCompleted,
        boolean emailTaskCommented,
        boolean emailTaskDueSoon,
        boolean emailTaskOverdue,
        boolean emailProjectAssigned,
        boolean emailLeaveUpdates
) {
}
