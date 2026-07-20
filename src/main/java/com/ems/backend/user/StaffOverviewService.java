package com.ems.backend.user;

import com.ems.backend.attendance.AttendanceSessionService;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.dailylog.DailyLogService;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.leave.LeaveRequestService;
import com.ems.backend.leave.LeaveStatus;
import com.ems.backend.leave.LeaveType;
import com.ems.backend.leave.dto.LeaveRequestResponse;
import com.ems.backend.project.ProjectService;
import com.ems.backend.project.ProjectStatus;
import com.ems.backend.project.dto.ProjectResponse;
import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.task.TaskService;
import com.ems.backend.task.TaskStatus;
import com.ems.backend.task.dto.TaskResponse;
import com.ems.backend.user.dto.StaffOverviewResponse;
import com.ems.backend.user.dto.StaffAuditEventResponse;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class StaffOverviewService {
    private final UserRepository userRepository;
    private final ProjectService projectService;
    private final TaskService taskService;
    private final AttendanceSessionService attendanceSessionService;
    private final LeaveRequestService leaveRequestService;
    private final AppSettingsService settingsService;
    private final DailyLogService dailyLogService;
    private final StaffDocumentRepository staffDocumentRepository;
    private final StaffAuditEventRepository staffAuditEventRepository;
    private final UserService userService;
    private final SecurityUtils securityUtils;
    private final AuthorizationPolicyService authorizationPolicy;
    private final SecurityAuditService auditService;

    public StaffOverviewService(
            UserRepository userRepository,
            ProjectService projectService,
            TaskService taskService,
            AttendanceSessionService attendanceSessionService,
            LeaveRequestService leaveRequestService,
            AppSettingsService settingsService,
            DailyLogService dailyLogService,
            StaffDocumentRepository staffDocumentRepository,
            StaffAuditEventRepository staffAuditEventRepository,
            UserService userService,
            SecurityUtils securityUtils,
            AuthorizationPolicyService authorizationPolicy,
            SecurityAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.projectService = projectService;
        this.taskService = taskService;
        this.attendanceSessionService = attendanceSessionService;
        this.leaveRequestService = leaveRequestService;
        this.settingsService = settingsService;
        this.dailyLogService = dailyLogService;
        this.staffDocumentRepository = staffDocumentRepository;
        this.staffAuditEventRepository = staffAuditEventRepository;
        this.userService = userService;
        this.securityUtils = securityUtils;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public StaffOverviewResponse getOverview(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Staff member not found"));
        User actor = securityUtils.getCurrentUser();
        if (!authorizationPolicy.canViewEmployeePrivateProfile(actor, user)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "You do not have permission to view this private staff record."
            );
        }

        List<ProjectResponse> projects = projectService.getProjectsForStaff(userId);
        List<TaskResponse> tasks = taskService.getTasksByAssignee(userId);
        List<AttendanceSessionResponse> attendance = attendanceSessionService.getSessionsByUser(userId);
        List<LeaveRequestResponse> leaveRequests = leaveRequestService.getRequestsForUser(userId);
        List<DailyLogResponse> dailyLogs = dailyLogService.getLogsByUser(userId);

        StaffOverviewResponse response = new StaffOverviewResponse(
                userService.map(user),
                buildSummary(user, projects, tasks, attendance, leaveRequests, dailyLogs),
                projects,
                tasks,
                attendance,
                leaveRequests,
                dailyLogs,
                staffDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(userService::mapDocument)
                        .toList(),
                staffAuditEventRepository.findByStaffUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(event -> new StaffAuditEventResponse(
                                event.getId(),
                                event.getAction(),
                                event.getDescription(),
                                event.getActor() != null ? event.getActor().getFullName() : "System",
                                event.getCreatedAt()
                        ))
                        .toList()
        );
        auditService.record(
                actor.getId(), user.getId(), "PRIVATE_STAFF_RECORD_VIEWED", "SUCCESS",
                actor.getRole() == Role.ADMIN ? "ADMIN_ACCESS" : "SELF_ACCESS",
                user.getEmail(), RequestMetadata.current()
        );
        return response;
    }

    private StaffOverviewResponse.Summary buildSummary(
            User user,
            List<ProjectResponse> projects,
            List<TaskResponse> tasks,
            List<AttendanceSessionResponse> attendance,
            List<LeaveRequestResponse> leaveRequests,
            List<DailyLogResponse> dailyLogs
    ) {
        Instant thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 60 * 60);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int currentYear = today.getYear();

        List<AttendanceSessionResponse> recentAttendance = attendance.stream()
                .filter(session -> !session.startTime().isBefore(thirtyDaysAgo))
                .toList();
        BigDecimal recentHours = recentAttendance.stream()
                .map(AttendanceSessionResponse::totalHours)
                .filter(hours -> hours != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int attendanceDays = (int) recentAttendance.stream()
                .map(session -> session.startTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();
        Instant lastAttendanceAt = attendance.stream()
                .map(AttendanceSessionResponse::startTime)
                .max(Instant::compareTo)
                .orElse(null);
        int approvedLeaveDays = leaveRequests.stream()
                .filter(request -> request.status() == LeaveStatus.APPROVED)
                .filter(request -> request.startDate().getYear() == currentYear
                        || request.endDate().getYear() == currentYear)
                .mapToInt(LeaveRequestResponse::requestedDays)
                .sum();
        int approvedAnnualLeaveDays = leaveRequests.stream()
                .filter(request -> request.status() == LeaveStatus.APPROVED)
                .filter(request -> request.leaveType() == LeaveType.ANNUAL)
                .filter(request -> request.startDate().getYear() == currentYear
                        || request.endDate().getYear() == currentYear)
                .mapToInt(LeaveRequestResponse::requestedDays)
                .sum();
        int approvedSickLeaveDays = leaveRequests.stream()
                .filter(request -> request.status() == LeaveStatus.APPROVED)
                .filter(request -> request.leaveType() == LeaveType.SICK)
                .filter(request -> request.startDate().getYear() == currentYear
                        || request.endDate().getYear() == currentYear)
                .mapToInt(LeaveRequestResponse::requestedDays)
                .sum();
        int annualLeaveAllowance = Math.max(settingsService.annualLeaveDays()
                + (user.getLeaveBalanceAdjustmentDays() != null ? user.getLeaveBalanceAdjustmentDays() : 0), 0);
        int sickLeaveAllowance = Math.max(settingsService.sickLeaveDays()
                + (user.getSickLeaveBalanceAdjustmentDays() != null ? user.getSickLeaveBalanceAdjustmentDays() : 0), 0);

        return new StaffOverviewResponse.Summary(
                projects.size(),
                (int) projects.stream().filter(project -> project.status() == ProjectStatus.ACTIVE).count(),
                tasks.size(),
                (int) tasks.stream().filter(task -> TaskStatus.DONE.name().equals(task.status())).count(),
                (int) tasks.stream()
                        .filter(task -> !TaskStatus.DONE.name().equals(task.status()))
                        .filter(task -> task.dueDate() != null && task.dueDate().isBefore(today))
                        .count(),
                recentHours,
                attendanceDays,
                lastAttendanceAt,
                approvedLeaveDays,
                approvedAnnualLeaveDays,
                approvedSickLeaveDays,
                annualLeaveAllowance,
                Math.max(annualLeaveAllowance - approvedAnnualLeaveDays, 0),
                sickLeaveAllowance,
                Math.max(sickLeaveAllowance - approvedSickLeaveDays, 0),
                (int) leaveRequests.stream().filter(request -> request.status() == LeaveStatus.PENDING).count(),
                dailyLogs.size(),
                dailyLogs.stream().map(DailyLogResponse::logDate).max(LocalDate::compareTo).orElse(null)
        );
    }

}
