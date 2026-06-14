package com.ems.backend.user.dto;

public record UpdateNotificationPreferencesRequest(
        Boolean emailTaskAssigned,
        Boolean emailTaskCompleted,
        Boolean emailTaskCommented,
        Boolean emailTaskDueSoon,
        Boolean emailTaskOverdue,
        Boolean emailProjectAssigned,
        Boolean emailLeaveUpdates
) {
}
