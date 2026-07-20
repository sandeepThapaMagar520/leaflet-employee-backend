package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceDaySummaryResponse;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.attendance.dto.AttendanceCorrectionResponse;
import com.ems.backend.attendance.dto.CreateAttendanceCorrectionRequest;
import com.ems.backend.attendance.dto.ReviewAttendanceCorrectionRequest;
import com.ems.backend.attendance.dto.AttendanceOverrideRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    private final AttendanceCorrectionService correctionService;

    public AttendanceSessionController(AttendanceSessionService service, AttendanceCorrectionService correctionService) {
        this.service = service;
        this.correctionService = correctionService;
    }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Start work session")
    public AttendanceSessionResponse startSession() {
        return service.startSession();
    }

    @PostMapping("/users/{userId}/active/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Start a team member's work session")
    public AttendanceSessionResponse startUserActiveSession(
            @PathVariable Long userId,
            @Valid @RequestBody AttendanceOverrideRequest request
    ) {
        return service.startUserActiveSession(userId, request.reason());
    }

    @PostMapping("/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Stop work session")
    public AttendanceSessionResponse endSession() {
        return service.endSession();
    }

    @PostMapping("/heartbeat")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Refresh active session heartbeat")
    public AttendanceSessionResponse heartbeat() {
        return service.heartbeat();
    }

    @PostMapping("/break/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Start break for active work session")
    public AttendanceSessionResponse startBreak() {
        return service.startBreak();
    }

    @PostMapping("/break/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "End break for active work session")
    public AttendanceSessionResponse endBreak() {
        return service.endBreak();
    }

    @PatchMapping("/users/{userId}/active/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Stop a team member's active work session")
    public AttendanceSessionResponse endUserActiveSession(
            @PathVariable Long userId,
            @Valid @RequestBody AttendanceOverrideRequest request
    ) {
        return service.endUserActiveSession(userId, request.reason());
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

    @PostMapping("/corrections")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Request an attendance session correction")
    public AttendanceCorrectionResponse createCorrection(@Valid @RequestBody CreateAttendanceCorrectionRequest request) {
        return correctionService.create(request);
    }

    @GetMapping("/corrections")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List attendance corrections", description = "Employees see their own requests; admins and managers see all requests.")
    public List<AttendanceCorrectionResponse> listCorrections() {
        return correctionService.list();
    }

    @PatchMapping("/corrections/{correctionId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Approve an attendance correction")
    public AttendanceCorrectionResponse approveCorrection(
            @PathVariable Long correctionId,
            @Valid @RequestBody ReviewAttendanceCorrectionRequest request
    ) {
        return correctionService.approve(correctionId, request);
    }

    @PatchMapping("/corrections/{correctionId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Reject an attendance correction")
    public AttendanceCorrectionResponse rejectCorrection(
            @PathVariable Long correctionId,
            @Valid @RequestBody ReviewAttendanceCorrectionRequest request
    ) {
        return correctionService.reject(correctionId, request);
    }
}
