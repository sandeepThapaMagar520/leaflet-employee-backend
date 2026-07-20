package com.ems.backend.authorization;

import com.ems.backend.attendance.AttendanceCorrectionRequest;
import com.ems.backend.leave.LeaveRequest;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.StaffDocument;
import com.ems.backend.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthorizationPolicyService {
    private final ManagerEmployeeScopeRepository scopeRepository;
    private final SecurityAuditService auditService;

    public AuthorizationPolicyService(
            ManagerEmployeeScopeRepository scopeRepository,
            SecurityAuditService auditService
    ) {
        this.scopeRepository = scopeRepository;
        this.auditService = auditService;
    }

    public boolean manages(User actor, User employee) {
        return actor != null
                && employee != null
                && actor.getRole() == Role.MANAGER
                && scopeRepository.existsByManagerIdAndEmployeeIdAndActiveTrue(
                        actor.getId(), employee.getId()
                );
    }

    public boolean canViewEmployee(User actor, User employee) {
        return isAdministrator(actor) || isSelf(actor, employee) || manages(actor, employee);
    }

    public boolean canViewEmployeeDirectoryEntry(User actor, User employee) {
        return canViewEmployee(actor, employee);
    }

    public boolean canViewEmployeePrivateProfile(User actor, User employee) {
        return isAdministrator(actor) || isSelf(actor, employee);
    }

    public boolean canEditEmployeeRecord(User actor, User employee) {
        return isAdministrator(actor);
    }

    public boolean canViewDailyLog(User actor, User employee) {
        return canViewEmployee(actor, employee);
    }

    public boolean canEditDailyLog(User actor, User employee) {
        return isSelf(actor, employee) || manages(actor, employee);
    }

    public boolean canViewAttendance(User actor, User employee) {
        return canViewEmployee(actor, employee);
    }

    public boolean canManageAttendance(User actor, User employee) {
        if (actor == null || employee == null || isSelf(actor, employee)) {
            return false;
        }
        return isAdministrator(actor) || manages(actor, employee);
    }

    public boolean canReviewAttendanceCorrection(
            User actor,
            AttendanceCorrectionRequest correction
    ) {
        return correction != null
                && canReviewEmployeeRequest(actor, correction.getUser());
    }

    public boolean canViewLeave(User actor, User employee) {
        return canViewEmployee(actor, employee);
    }

    public boolean canReviewLeave(User actor, LeaveRequest request) {
        return request != null && canReviewEmployeeRequest(actor, request.getUser());
    }

    public boolean canViewHrDocument(User actor, User employee, StaffDocument document) {
        return document != null
                && document.getUser().getId().equals(employee.getId())
                && (isAdministrator(actor) || isSelf(actor, employee));
    }

    public boolean canManageHrDocument(User actor, User employee, StaffDocument document) {
        return isAdministrator(actor)
                && document != null
                && document.getUser().getId().equals(employee.getId());
    }

    public boolean canExportEmployeeData(User actor, User employee) {
        return canViewEmployee(actor, employee);
    }

    public void requireViewEmployee(User actor, User employee, String resourceType) {
        if (!canViewEmployee(actor, employee)) {
            deny(actor, employee, "CROSS_SCOPE_ACCESS_DENIED", resourceType);
        }
    }

    public void requireViewDailyLog(User actor, User employee) {
        if (!canViewDailyLog(actor, employee)) {
            deny(actor, employee, "CROSS_SCOPE_DAILY_LOG_DENIED", "daily-log");
        }
    }

    public void requireEditDailyLog(User actor, User employee) {
        if (!canEditDailyLog(actor, employee)) {
            deny(actor, employee, "CROSS_SCOPE_DAILY_LOG_EDIT_DENIED", "daily-log");
        }
    }

    public void requireViewAttendance(User actor, User employee) {
        if (!canViewAttendance(actor, employee)) {
            deny(actor, employee, "CROSS_SCOPE_ATTENDANCE_DENIED", "attendance");
        }
    }

    public void requireManageAttendance(User actor, User employee) {
        if (!canManageAttendance(actor, employee)) {
            String reason = isSelf(actor, employee)
                    ? "SELF_ATTENDANCE_OVERRIDE"
                    : "CROSS_SCOPE_ATTENDANCE_OVERRIDE";
            deny(actor, employee, reason, "attendance");
        }
    }

    public void requireViewLeave(User actor, User employee) {
        if (!canViewLeave(actor, employee)) {
            deny(actor, employee, "CROSS_SCOPE_LEAVE_DENIED", "leave");
        }
    }

    public void requireReviewLeave(User actor, LeaveRequest request) {
        if (!canReviewLeave(actor, request)) {
            String reason = isSelf(actor, request.getUser())
                    ? "SELF_LEAVE_REVIEW"
                    : "CROSS_SCOPE_LEAVE_REVIEW";
            deny(actor, request.getUser(), reason, "leave-request:" + request.getId());
        }
    }

    public void requireReviewAttendanceCorrection(
            User actor,
            AttendanceCorrectionRequest correction
    ) {
        if (!canReviewAttendanceCorrection(actor, correction)) {
            String reason = isSelf(actor, correction.getUser())
                    ? "SELF_ATTENDANCE_CORRECTION_REVIEW"
                    : "CROSS_SCOPE_ATTENDANCE_CORRECTION_REVIEW";
            deny(
                    actor,
                    correction.getUser(),
                    reason,
                    "attendance-correction:" + correction.getId()
            );
        }
    }

    private boolean canReviewEmployeeRequest(User actor, User employee) {
        if (actor == null || employee == null || isSelf(actor, employee)) {
            return false;
        }
        return isAdministrator(actor) || manages(actor, employee);
    }

    private boolean isAdministrator(User user) {
        return user != null && user.getRole() == Role.ADMIN;
    }

    private boolean isSelf(User actor, User employee) {
        return actor != null
                && employee != null
                && actor.getId().equals(employee.getId());
    }

    private void deny(User actor, User target, String reason, String resource) {
        auditService.recordBestEffort(
                actor == null ? null : actor.getId(),
                target == null ? null : target.getId(),
                "AUTHORIZATION_DENIED",
                "DENIED",
                reason,
                target == null ? null : target.getEmail(),
                RequestMetadata.current()
        );
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access or modify this resource."
        );
    }
}
