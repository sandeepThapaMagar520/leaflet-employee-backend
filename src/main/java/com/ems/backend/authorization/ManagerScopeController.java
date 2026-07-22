package com.ems.backend.authorization;

import com.ems.backend.authorization.dto.AssignManagerScopeRequest;
import com.ems.backend.authorization.dto.ManagerScopeResponse;
import com.ems.backend.common.PageResponse;
import com.ems.backend.user.dto.ManagerDirectoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/manager-scopes")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Manager Scopes", description = "Administrator-controlled reporting relationships")
public class ManagerScopeController {
    private final ManagerScopeService service;

    public ManagerScopeController(ManagerScopeService service) {
        this.service = service;
    }

    @PutMapping("/employees/{employeeId}")
    @Operation(summary = "Assign or transactionally reassign an employee")
    public ManagerScopeResponse assign(
            @PathVariable Long employeeId,
            @Valid @RequestBody AssignManagerScopeRequest request
    ) {
        return service.assignOrReassign(employeeId, request.managerId());
    }

    @DeleteMapping("/employees/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an employee's active manager assignment")
    public void remove(@PathVariable Long employeeId) {
        service.remove(employeeId);
    }

    @GetMapping("/employees/{employeeId}")
    @Operation(summary = "Get an employee's active manager assignment")
    public ManagerScopeResponse getEmployeeScope(@PathVariable Long employeeId) {
        return service.getActiveForEmployee(employeeId);
    }

    @GetMapping("/managers/{managerId}/employees")
    @Operation(summary = "List employees assigned to a manager")
    public PageResponse<ManagerDirectoryResponse> listManagerEmployees(
            @PathVariable Long managerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listManagerEmployees(managerId, page, size);
    }

    @GetMapping("/managers")
    @Operation(summary = "List active managers available for assignment")
    public PageResponse<ManagerDirectoryResponse> listManagers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listAvailableManagers(page, size);
    }
}
