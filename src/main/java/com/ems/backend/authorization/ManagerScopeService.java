package com.ems.backend.authorization;

import com.ems.backend.authorization.dto.ManagerScopeResponse;
import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.user.dto.ManagerDirectoryResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class ManagerScopeService {
    private final ManagerEmployeeScopeRepository scopeRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final SecurityAuditService auditService;

    public ManagerScopeService(
            ManagerEmployeeScopeRepository scopeRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            SecurityAuditService auditService
    ) {
        this.scopeRepository = scopeRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.auditService = auditService;
    }

    @Transactional
    public ManagerScopeResponse assignOrReassign(Long employeeId, Long managerId) {
        User actor = requireAdministrator();
        User employee = userRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found."));
        User manager = userRepository.findByIdForUpdate(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Manager not found."));
        validateAssignment(actor, manager, employee);

        ManagerEmployeeScope existing =
                scopeRepository.findActiveByEmployeeIdForUpdate(employeeId).orElse(null);
        if (existing != null && existing.getManager().getId().equals(managerId)) {
            return map(existing);
        }

        Instant now = Instant.now();
        Long previousManagerId = null;
        if (existing != null) {
            previousManagerId = existing.getManager().getId();
            existing.setActive(false);
            existing.setEndedAt(now);
            scopeRepository.save(existing);
            scopeRepository.flush();
        }

        ManagerEmployeeScope created = new ManagerEmployeeScope();
        created.setManager(manager);
        created.setEmployee(employee);
        created.setAssignedBy(actor);
        created.setAssignedAt(now);
        created.setActive(true);
        try {
            created = scopeRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The employee already has an active manager assignment."
            );
        }

        auditService.recordWithDetails(
                actor.getId(),
                employee.getId(),
                previousManagerId == null ? "MANAGER_SCOPE_ASSIGNED" : "MANAGER_SCOPE_REASSIGNED",
                "SUCCESS",
                previousManagerId == null ? "ADMIN_ASSIGNMENT" : "ADMIN_REASSIGNMENT",
                "managerId=%d,previousManagerId=%s".formatted(managerId, previousManagerId),
                employee.getEmail(),
                RequestMetadata.current()
        );
        return map(created);
    }

    @Transactional
    public void remove(Long employeeId) {
        User actor = requireAdministrator();
        User employee = userRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found."));
        ManagerEmployeeScope existing =
                scopeRepository.findActiveByEmployeeIdForUpdate(employeeId).orElse(null);
        if (existing == null) {
            return;
        }
        existing.setActive(false);
        existing.setEndedAt(Instant.now());
        scopeRepository.save(existing);
        auditService.recordWithDetails(
                actor.getId(),
                employee.getId(),
                "MANAGER_SCOPE_REMOVED",
                "SUCCESS",
                "ADMIN_REMOVAL",
                "managerId=" + existing.getManager().getId(),
                employee.getEmail(),
                RequestMetadata.current()
        );
    }

    @Transactional(readOnly = true)
    public ManagerScopeResponse getActiveForEmployee(Long employeeId) {
        requireAdministrator();
        return scopeRepository.findByEmployeeIdAndActiveTrue(employeeId)
                .map(this::map)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ManagerDirectoryResponse> listManagerEmployees(
            Long managerId,
            int page,
            int size
    ) {
        requireAdministrator();
        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Manager not found."));
        if (manager.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected user is not a manager.");
        }
        var pageable = Pagination.page(page, size, "employee.fullName", "asc", java.util.Set.of("employee.fullName"));
        return PageResponse.from(
                scopeRepository.findByManagerIdAndActiveTrue(managerId, pageable),
                scope -> mapDirectory(scope.getEmployee())
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<ManagerDirectoryResponse> listAvailableManagers(int page, int size) {
        requireAdministrator();
        var pageable = Pagination.page(page, size, "fullName", "asc", java.util.Set.of("fullName"));
        return PageResponse.from(userRepository.findByRoleAndActiveTrue(Role.MANAGER, pageable), this::mapDirectory);
    }

    private User requireAdministrator() {
        User actor = securityUtils.getCurrentUser();
        if (actor.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required.");
        }
        return actor;
    }

    private void validateAssignment(User actor, User manager, User employee) {
        if (manager.getId().equals(employee.getId())) {
            auditDenied(actor, employee, "SELF_MANAGER_ASSIGNMENT");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A user cannot manage themselves.");
        }
        if (manager.getRole() != Role.MANAGER || !Boolean.TRUE.equals(manager.getActive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manager must be an active MANAGER.");
        }
        if (employee.getRole() != Role.EMPLOYEE || !Boolean.TRUE.equals(employee.getActive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target must be an active EMPLOYEE.");
        }
    }

    private void auditDenied(User actor, User employee, String reason) {
        auditService.recordBestEffort(
                actor.getId(),
                employee.getId(),
                "MANAGER_SCOPE_CHANGE_DENIED",
                "DENIED",
                reason,
                employee.getEmail(),
                RequestMetadata.current()
        );
    }

    private ManagerScopeResponse map(ManagerEmployeeScope scope) {
        return new ManagerScopeResponse(
                scope.getId(),
                scope.getManager().getId(),
                scope.getManager().getFullName(),
                scope.getEmployee().getId(),
                scope.getEmployee().getFullName(),
                scope.isActive(),
                scope.getAssignedAt(),
                scope.getEndedAt(),
                scope.getVersion()
        );
    }

    private ManagerDirectoryResponse mapDirectory(User user) {
        return new ManagerDirectoryResponse(
                user.getId(),
                user.getEmployeeId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getJobTitle(),
                user.getDepartment(),
                user.getActive(),
                user.getProfilePhotoUrl()
        );
    }
}
