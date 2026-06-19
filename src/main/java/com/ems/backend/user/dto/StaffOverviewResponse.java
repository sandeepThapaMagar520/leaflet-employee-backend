package com.ems.backend.user.dto;

import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.leave.dto.LeaveRequestResponse;
import com.ems.backend.project.dto.ProjectResponse;
import com.ems.backend.task.dto.TaskResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record StaffOverviewResponse(
        UserResponse staff,
        Summary summary,
        List<ProjectResponse> projects,
        List<TaskResponse> tasks,
        List<AttendanceSessionResponse> attendanceSessions,
        List<LeaveRequestResponse> leaveRequests,
        List<DailyLogResponse> dailyLogs,
        List<StaffDocumentResponse> documents,
        List<StaffAuditEventResponse> auditEvents
) {
    public record Summary(
            int projectCount,
            int activeProjectCount,
            int taskCount,
            int completedTaskCount,
            int overdueTaskCount,
            BigDecimal attendanceHoursLast30Days,
            int attendanceDaysLast30Days,
            Instant lastAttendanceAt,
            int approvedLeaveDaysThisYear,
            int pendingLeaveRequests,
            int dailyLogCount,
            LocalDate latestDailyLogDate
    ) {
    }
}
