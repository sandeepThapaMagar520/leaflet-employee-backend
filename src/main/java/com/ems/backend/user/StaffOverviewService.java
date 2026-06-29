package com.ems.backend.user;

import com.ems.backend.attendance.AttendanceSessionService;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.dailylog.DailyLogService;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.leave.LeaveRequestService;
import com.ems.backend.leave.LeaveStatus;
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
import org.springframework.stereotype.Service;
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
            UserService userService
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
    }

    public StaffOverviewResponse getOverview(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Staff member not found"));

        List<ProjectResponse> projects = projectService.getAllProjects().stream()
                .filter(project -> userId.equals(project.managerId())
                        || project.assignedEmployees().stream().anyMatch(employee -> userId.equals(employee.id())))
                .toList();
        List<TaskResponse> tasks = taskService.getAllTasks().stream()
                .filter(task -> userId.equals(task.assignedToId()))
                .toList();
        List<AttendanceSessionResponse> attendance = attendanceSessionService.getAllSessions().stream()
                .filter(session -> userId.equals(session.userId()))
                .toList();
        List<LeaveRequestResponse> leaveRequests = leaveRequestService.listRequests().stream()
                .filter(request -> userId.equals(request.userId()))
                .toList();
        List<DailyLogResponse> dailyLogs = dailyLogService.getAllLogs().stream()
                .filter(log -> userId.equals(log.userId()))
                .toList();

        return new StaffOverviewResponse(
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
        int annualLeaveAllowance = Math.max(settingsService.annualLeaveDays()
                + (user.getLeaveBalanceAdjustmentDays() != null ? user.getLeaveBalanceAdjustmentDays() : 0), 0);

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
                annualLeaveAllowance,
                Math.max(annualLeaveAllowance - approvedLeaveDays, 0),
                (int) leaveRequests.stream().filter(request -> request.status() == LeaveStatus.PENDING).count(),
                dailyLogs.size(),
                dailyLogs.stream().map(DailyLogResponse::logDate).max(LocalDate::compareTo).orElse(null)
        );
    }

}
