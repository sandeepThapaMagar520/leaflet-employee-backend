package com.ems.backend.export;

import com.ems.backend.attendance.AttendanceSessionService;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.dailylog.DailyLogService;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.user.StaffOverviewService;
import com.ems.backend.user.dto.StaffOverviewResponse;
import com.ems.backend.task.dto.TaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/exports")
@Tag(name = "Exports", description = "CSV export endpoints for attendance and daily logs")
public class ExportController {
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final AttendanceSessionService attendanceSessionService;
    private final DailyLogService dailyLogService;
    private final StaffOverviewService staffOverviewService;

    public ExportController(
            AttendanceSessionService attendanceSessionService,
            DailyLogService dailyLogService,
            StaffOverviewService staffOverviewService
    ) {
        this.attendanceSessionService = attendanceSessionService;
        this.dailyLogService = dailyLogService;
        this.staffOverviewService = staffOverviewService;
    }

    @GetMapping("/staff/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Export staff record CSV", description = "Exports one staff member's profile, workload, and record counts.")
    public ResponseEntity<byte[]> exportStaffRecord(@PathVariable Long id) {
        StaffOverviewResponse record = staffOverviewService.getOverview(id);
        StringBuilder csv = new StringBuilder("Section,Field,Value\n");
        csv.append(csvRow("Profile", "Name", record.staff().fullName()));
        csv.append(csvRow("Profile", "Email", record.staff().email()));
        csv.append(csvRow("Profile", "Employee ID", record.staff().employeeId()));
        csv.append(csvRow("Profile", "Role", record.staff().role().name()));
        csv.append(csvRow("Profile", "Account status", record.staff().accountStatus().name()));
        csv.append(csvRow("Profile", "Employment type", record.staff().employmentType().name()));
        csv.append(csvRow("Profile", "Joining date", record.staff().joiningDate() != null ? record.staff().joiningDate().toString() : ""));
        csv.append(csvRow("Profile", "Phone", record.staff().phone()));
        csv.append(csvRow("Profile", "Emergency contact", record.staff().emergencyContact()));
        csv.append(csvRow("Profile", "Location", record.staff().location()));
        csv.append(csvRow("Workload", "Active projects", String.valueOf(record.summary().activeProjectCount())));
        csv.append(csvRow("Workload", "Total tasks", String.valueOf(record.summary().taskCount())));
        csv.append(csvRow("Workload", "Completed tasks", String.valueOf(record.summary().completedTaskCount())));
        csv.append(csvRow("Workload", "Overdue tasks", String.valueOf(record.summary().overdueTaskCount())));
        csv.append(csvRow("Records", "Attendance sessions", String.valueOf(record.attendanceSessions().size())));
        csv.append(csvRow("Records", "Leave requests", String.valueOf(record.leaveRequests().size())));
        csv.append(csvRow("Records", "DSU submissions", String.valueOf(record.dailyLogs().size())));
        csv.append(csvRow("Records", "Documents", String.valueOf(record.documents().size())));
        for (TaskResponse task : record.tasks()) {
            csv.append(csvRow("Task", task.title(), task.projectName() + " · " + task.status()));
        }
        return csvAttachment("staff-record-" + id + ".csv", csv.toString());
    }

    @GetMapping("/attendance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Export attendance CSV", description = "Exports visible attendance sessions. Optional from/to date filters use yyyy-MM-dd.")
    public ResponseEntity<byte[]> exportAttendance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        List<AttendanceSessionResponse> sessions = attendanceSessionService.getSessionsForExport().stream()
                .filter(session -> isWithinRange(LocalDate.ofInstant(session.startTime(), ZoneId.systemDefault()), from, to))
                .toList();
        StringBuilder csv = new StringBuilder("Employee,Start Time,End Time,Total Hours\n");
        for (AttendanceSessionResponse session : sessions) {
            csv.append(csvCell(session.userFullName())).append(',')
                    .append(csvCell(CSV_DATE.format(session.startTime()))).append(',')
                    .append(csvCell(session.endTime() != null ? CSV_DATE.format(session.endTime()) : "")).append(',')
                    .append(csvCell(session.totalHours() != null ? session.totalHours().toPlainString() : ""))
                    .append('\n');
        }
        return csvAttachment("attendance-export.csv", csv.toString());
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Export daily logs CSV", description = "Exports visible daily logs. Optional from/to date filters use yyyy-MM-dd.")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        List<DailyLogResponse> logs = dailyLogService.getLogsForExport().stream()
                .filter(log -> isWithinRange(log.logDate(), from, to))
                .toList();
        StringBuilder csv = new StringBuilder("Employee,Log Date,Summary,Problems Faced,Created At\n");
        for (DailyLogResponse log : logs) {
            csv.append(csvCell(log.userFullName())).append(',')
                    .append(csvCell(log.logDate().toString())).append(',')
                    .append(csvCell(log.summary())).append(',')
                    .append(csvCell(log.problemsFaced() != null ? log.problemsFaced() : "")).append(',')
                    .append(csvCell(CSV_DATE.format(log.createdAt())))
                    .append('\n');
        }
        return csvAttachment("daily-logs-export.csv", csv.toString());
    }

    private static ResponseEntity<byte[]> csvAttachment(String filename, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String csvRow(String section, String field, String value) {
        return csvCell(section) + "," + csvCell(field) + "," + csvCell(value) + "\n";
    }

    private static boolean isWithinRange(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }
}
