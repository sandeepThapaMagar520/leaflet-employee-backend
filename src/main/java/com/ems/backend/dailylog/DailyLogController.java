package com.ems.backend.dailylog;

import com.ems.backend.dailylog.dto.CreateDailyLogRequest;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.dailylog.dto.UpdateDailyLogRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/logs")
@Tag(name = "Daily Logs", description = "Employee daily work logs and admin audit views")
public class DailyLogController {
    private final DailyLogService service;

    public DailyLogController(DailyLogService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Submit daily log", description = "Employees and managers can submit daily logs. Admin submission is blocked by business rules.")
    public DailyLogResponse createLog(@Valid @RequestBody CreateDailyLogRequest request) {
        return service.createLog(request);
    }

    @PutMapping("/{logId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Update daily log", description = "Owners and managers can edit allowed logs. Admin editing is blocked by business rules.")
    public DailyLogResponse updateLog(
            @PathVariable Long logId,
            @Valid @RequestBody UpdateDailyLogRequest request
    ) {
        return service.updateLog(logId, request);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List my daily logs")
    public List<DailyLogResponse> getMyLogs() {
        return service.getMyLogs();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Audit all daily logs", description = "Admin and manager view for reviewing team daily log submissions.")
    public List<DailyLogResponse> getAllLogs() {
        return service.getAllLogs();
    }
}
