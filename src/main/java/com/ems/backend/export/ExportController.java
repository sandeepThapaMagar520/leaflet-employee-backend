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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/exports")
@Tag(name = "Exports", description = "CSV export endpoints for attendance and daily logs")
public class ExportController {
    private static final int EXPORT_BATCH_SIZE = 100;
    private static final int MAX_EXPORT_ROWS = 100_000;
    private static final long MAX_EXPORT_DAYS = 366;
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
    public ResponseEntity<StreamingResponseBody> exportAttendance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireSafeRange(from, to);
        StreamingResponseBody body = output -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write("Employee,Start Time,End Time,Total Hours\n");
            int page = 0;
            int written = 0;
            boolean last;
            do {
                var batch = attendanceSessionService.getSessionsForExport(from, to, page++, EXPORT_BATCH_SIZE);
                for (AttendanceSessionResponse session : batch.content()) {
                    if (++written > MAX_EXPORT_ROWS) throw new IllegalStateException("Export row limit exceeded");
                    writer.write(csvCell(session.userFullName()) + ","
                            + csvCell(CSV_DATE.format(session.startTime())) + ","
                            + csvCell(session.endTime() != null ? CSV_DATE.format(session.endTime()) : "") + ","
                            + csvCell(session.totalHours() != null ? session.totalHours().toPlainString() : "") + "\n");
                }
                writer.flush();
                last = batch.last();
            } while (!last);
        };
        return csvStreamAttachment("attendance-export.csv", secured(body));
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Export daily logs CSV", description = "Exports visible daily logs. Optional from/to date filters use yyyy-MM-dd.")
    public ResponseEntity<StreamingResponseBody> exportLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireSafeRange(from, to);
        StreamingResponseBody body = output -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write("Employee,Log Date,Summary,Problems Faced,Created At\n");
            int page = 0;
            int written = 0;
            boolean last;
            do {
                var batch = dailyLogService.getLogsForExport(from, to, page++, EXPORT_BATCH_SIZE);
                for (DailyLogResponse log : batch.content()) {
                    if (++written > MAX_EXPORT_ROWS) throw new IllegalStateException("Export row limit exceeded");
                    writer.write(csvCell(log.userFullName()) + "," + csvCell(log.logDate().toString()) + ","
                            + csvCell(log.summary()) + ","
                            + csvCell(log.problemsFaced() != null ? log.problemsFaced() : "") + ","
                            + csvCell(CSV_DATE.format(log.createdAt())) + "\n");
                }
                writer.flush();
                last = batch.last();
            } while (!last);
        };
        return csvStreamAttachment("daily-logs-export.csv", secured(body));
    }

    private static ResponseEntity<byte[]> csvAttachment(String filename, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    private static ResponseEntity<StreamingResponseBody> csvStreamAttachment(String filename, StreamingResponseBody body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static StreamingResponseBody secured(StreamingResponseBody delegate) {
        SecurityContext captured = SecurityContextHolder.createEmptyContext();
        captured.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        return output -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            try {
                SecurityContextHolder.setContext(captured);
                delegate.writeTo(output);
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        };
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value;
        if (!escaped.isEmpty() && "=+-@".indexOf(escaped.charAt(0)) >= 0) {
            escaped = "'" + escaped;
        }
        return "\"" + escaped.replace("\"", "\"\"") + "\"";
    }

    private static String csvRow(String section, String field, String value) {
        return csvCell(section) + "," + csvCell(field) + "," + csvCell(value) + "\n";
    }

    private static void requireSafeRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(BAD_REQUEST, "from and to are required for exports");
        }
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_EXPORT_DAYS) {
            throw new ResponseStatusException(BAD_REQUEST, "Export date range must be valid and at most 366 days");
        }
    }
}
