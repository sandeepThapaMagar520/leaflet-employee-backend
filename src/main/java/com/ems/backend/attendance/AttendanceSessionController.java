package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceDaySummaryResponse;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Attendance", description = "Remote work sessions, daily duration summaries, and attendance audit endpoints")
public class AttendanceSessionController {
    private final AttendanceSessionService service;

    public AttendanceSessionController(AttendanceSessionService service) {
        this.service = service;
    }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Start work session")
    public AttendanceSessionResponse startSession() {
        return service.startSession();
    }

    @PostMapping("/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Stop work session")
    public AttendanceSessionResponse endSession() {
        return service.endSession();
    }

    @GetMapping("/me/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get today's attendance summary", description = "Totals today's work sessions against the 7-hour target and 6-hour grace threshold.")
    public AttendanceDaySummaryResponse getMyTodaySummary() {
        return service.getMyTodaySummary();
    }

    @GetMapping("/daily")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Get team daily attendance summary", description = "Shows each active user's daily worked minutes and completion status for the selected date.")
    public List<AttendanceDaySummaryResponse> getTeamDailySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return service.getTeamDailySummary(date);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List my attendance sessions")
    public List<AttendanceSessionResponse> getMySessions() {
        return service.getMySessions();
    }
    
    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get active attendance session")
    public ResponseEntity<AttendanceSessionResponse> getActiveSession() {
        AttendanceSessionResponse session = service.getActiveSession();
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(session);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Audit all attendance sessions")
    public List<AttendanceSessionResponse> getAllSessions() {
        return service.getAllSessions();
    }
}
