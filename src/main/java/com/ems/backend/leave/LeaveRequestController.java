package com.ems.backend.leave;

import com.ems.backend.leave.dto.CreateLeaveRequest;
import com.ems.backend.leave.dto.LeaveBalanceResponse;
import com.ems.backend.leave.dto.LeaveRequestResponse;
import com.ems.backend.leave.dto.UpdateLeaveBalanceRequest;
import com.ems.backend.leave.dto.UpdateLeaveStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leave-requests")
@Tag(name = "Leave Requests", description = "Leave submission, balance, approval, rejection, and cancellation")
public class LeaveRequestController {
    private final LeaveRequestService service;

    public LeaveRequestController(LeaveRequestService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Submit leave request", description = "Employees and managers can submit leave requests. Admin submission is blocked by business rules.")
    public LeaveRequestResponse createRequest(@Valid @RequestBody CreateLeaveRequest request) {
        return service.createRequest(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List leave requests", description = "Admins and managers see team requests. Employees see their own requests.")
    public List<LeaveRequestResponse> listRequests() {
        return service.listRequests();
    }

    @GetMapping("/balance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get my leave balance")
    public LeaveBalanceResponse getMyBalance() {
        return service.getMyBalance();
    }

    @GetMapping("/users/{userId}/balance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Get staff leave balance")
    public LeaveBalanceResponse getUserBalance(@PathVariable Long userId) {
        return service.getBalanceForUser(userId);
    }

    @PatchMapping("/users/{userId}/balance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update staff remaining leave balance")
    public LeaveBalanceResponse updateUserBalance(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateLeaveBalanceRequest request
    ) {
        return service.updateUserBalance(userId, request);
    }

    @PatchMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Approve leave request")
    public LeaveRequestResponse approve(
            @PathVariable Long requestId,
            @Valid @RequestBody UpdateLeaveStatusRequest request
    ) {
        return service.approve(requestId, request);
    }

    @PatchMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Reject leave request")
    public LeaveRequestResponse reject(
            @PathVariable Long requestId,
            @Valid @RequestBody UpdateLeaveStatusRequest request
    ) {
        return service.reject(requestId, request);
    }

    @PatchMapping("/{requestId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Cancel leave request")
    public LeaveRequestResponse cancel(@PathVariable Long requestId) {
        return service.cancel(requestId);
    }
}
