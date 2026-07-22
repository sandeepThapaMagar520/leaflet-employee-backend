package com.ems.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_notification_settings")
public class UserNotificationSettings {
    @Id
    private Long userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "email_task_assigned", nullable = false)
    private Boolean emailTaskAssigned = true;

    @Column(name = "email_task_completed", nullable = false)
    private Boolean emailTaskCompleted = true;

    @Column(name = "email_task_commented", nullable = false)
    private Boolean emailTaskCommented = false;

    @Column(name = "email_task_due_soon", nullable = false)
    private Boolean emailTaskDueSoon = true;

    @Column(name = "email_task_overdue", nullable = false)
    private Boolean emailTaskOverdue = true;

    @Column(name = "email_project_assigned", nullable = false)
    private Boolean emailProjectAssigned = true;

    @Column(name = "email_leave_updates", nullable = false)
    private Boolean emailLeaveUpdates = true;

    @Column(name = "email_attendance_updates", nullable = false)
    private Boolean emailAttendanceUpdates = true;
}
