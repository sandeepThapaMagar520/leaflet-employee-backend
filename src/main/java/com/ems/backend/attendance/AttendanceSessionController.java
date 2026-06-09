package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Attendance", description = "Clock-in, clock-out, active session, and attendance audit endpoints")
public class AttendanceSessionController {
    private final AttendanceSessionService service;

    public AttendanceSessionController(AttendanceSessionService service) {
        this.service = service;
    }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Start attendance session")
    public AttendanceSessionResponse startSession() {
        return service.startSession();
    }

    @PostMapping("/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "End attendance session")
    public AttendanceSessionResponse endSession() {
        return service.endSession();
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
