package com.ems.backend.leave;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.leave.dto.CreateLeaveRequest;
import com.ems.backend.leave.dto.LeaveBalanceResponse;
import com.ems.backend.leave.dto.LeaveRequestResponse;
import com.ems.backend.leave.dto.UpdateLeaveBalanceRequest;
import com.ems.backend.leave.dto.UpdateLeaveStatusRequest;
import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.time.BusinessClock;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class LeaveRequestService {
    private final LeaveRequestRepository repository;
    private final UserRepository userRepository;
    private final AppSettingsService settingsService;
    private final SecurityUtils securityUtils;
    private final AuthorizationPolicyService authorizationPolicy;
    private final SecurityAuditService auditService;
    private final LeaveDayCalculator dayCalculator;
    private final LeaveBalanceService balanceService;
    private final LeaveTransitionPolicy transitionPolicy;
    private final NotificationService notificationService;
    private final BusinessClock businessClock;

    public LeaveRequestService(
            LeaveRequestRepository repository,
            UserRepository userRepository,
            AppSettingsService settingsService,
            SecurityUtils securityUtils,
            AuthorizationPolicyService authorizationPolicy,
            SecurityAuditService auditService,
            LeaveDayCalculator dayCalculator,
            LeaveBalanceService balanceService,
            LeaveTransitionPolicy transitionPolicy,
            NotificationService notificationService,
            BusinessClock businessClock
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.securityUtils = securityUtils;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
        this.dayCalculator = dayCalculator;
        this.balanceService = balanceService;
        this.transitionPolicy = transitionPolicy;
        this.notificationService = notificationService;
        this.businessClock = businessClock;
    }

    public LeaveRequestResponse createRequest(CreateLeaveRequest request) {
        validateDates(request.startDate(), request.endDate());
        validateLeaveType(request.leaveType());
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins can review leave requests but cannot submit them.");
        }
        if (request.startDate().isBefore(businessClock.today())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Leave cannot start in the past");
        }
        userRepository.findByIdForUpdate(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Current user not found"));
        if (repository.existsBlockingOverlap(currentUser.getId(), request.startDate(), request.endDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leave request overlaps pending or approved leave");
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setUser(currentUser);
        leave.setLeaveType(request.leaveType());
        leave.setStartDate(request.startDate());
        leave.setEndDate(request.endDate());
        leave.setReason(request.reason());
        leave.setStatus(LeaveStatus.PENDING);
        LeaveRequest saved = repository.saveAndFlush(leave);
        recordEvent(currentUser, currentUser, "LEAVE_REQUESTED", "requestId=" + saved.getId());
        return map(saved, currentUser);
    }

    public List<LeaveRequestResponse> listRequests() {
        User currentUser = getCurrentUser();
        List<LeaveRequest> requests = switch (currentUser.getRole()) {
            case ADMIN -> repository.findAllByOrderByCreatedAtDesc();
            case MANAGER -> repository.findVisibleToManager(currentUser.getId());
            case EMPLOYEE -> repository.findByUserEmailIgnoreCaseOrderByCreatedAtDesc(
                    currentUser.getEmail()
            );
        };
        return requests.stream().map(request -> map(request, currentUser)).toList();
    }

    public List<LeaveRequestResponse> getRequestsForUser(Long userId) {
        User currentUser = getCurrentUser();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        authorizationPolicy.requireViewLeave(currentUser, target);
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(request -> map(request, currentUser))
                .toList();
    }

    public LeaveBalanceResponse getMyBalance() {
        User currentUser = getCurrentUser();
        return balanceFor(currentUser);
    }

    public LeaveBalanceResponse getBalanceForUser(Long userId) {
        User currentUser = getCurrentUser();
        User targetUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        authorizationPolicy.requireViewLeave(currentUser, targetUser);
        return balanceFor(targetUser);
    }

    public LeaveBalanceResponse updateUserBalance(Long userId, UpdateLeaveBalanceRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can update leave balances");
        }
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Integer annualRemainingDays = request.annualRemainingDays() != null ? request.annualRemainingDays() : request.remainingDays();
        Integer sickRemainingDays = request.sickRemainingDays();
        if (annualRemainingDays == null && sickRemainingDays == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one leave balance is required");
        }
        if (annualRemainingDays != null) {
            int approvedDays = balanceService.balance(targetUser, LeaveType.ANNUAL, businessClock.today()).used();
            targetUser.setLeaveBalanceAdjustmentDays(annualRemainingDays + approvedDays - settingsService.annualLeaveDays());
        }
        if (sickRemainingDays != null) {
            int sickApprovedDays = balanceService.balance(targetUser, LeaveType.SICK, businessClock.today()).used();
            targetUser.setSickLeaveBalanceAdjustmentDays(sickRemainingDays + sickApprovedDays - settingsService.sickLeaveDays());
        }
        User saved = userRepository.save(targetUser);
        return balanceFor(saved);
    }

    private LeaveBalanceResponse balanceFor(User user) {
        LeaveBalanceService.PeriodBalance annual = balanceService.balance(user, LeaveType.ANNUAL, businessClock.today());
        LeaveBalanceService.PeriodBalance sick = balanceService.balance(user, LeaveType.SICK, businessClock.today());
        return new LeaveBalanceResponse(
                annual.entitlement(),
                annual.used(),
                annual.remaining(),
                sick.entitlement(),
                sick.used(),
                sick.remaining()
        );
    }

    public LeaveRequestResponse approve(Long requestId, UpdateLeaveStatusRequest request) {
        return review(requestId, request.reviewerNote(), LeaveStatus.APPROVED);
    }

    public LeaveRequestResponse reject(Long requestId, UpdateLeaveStatusRequest request) {
        return review(requestId, request.reviewerNote(), LeaveStatus.REJECTED);
    }

    public LeaveRequestResponse cancel(Long requestId) {
        User currentUser = getCurrentUser();
        LeaveRequest leave = repository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));
        if (!leave.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can cancel only your own leave requests");
        }
        transitionPolicy.requireTransition(leave.getStatus(), LeaveStatus.CANCELLED);
        leave.setStatus(LeaveStatus.CANCELLED);
        LeaveRequest saved = repository.saveAndFlush(leave);
        recordEvent(currentUser, leave.getUser(), "LEAVE_CANCELLED", "requestId=" + leave.getId());
        return map(saved, currentUser);
    }

    private LeaveRequestResponse review(Long requestId, String reviewerNote, LeaveStatus status) {
        User currentUser = getCurrentUser();
        LeaveRequest leave = repository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));
        authorizationPolicy.requireReviewLeave(currentUser, leave);
        transitionPolicy.requireTransition(leave.getStatus(), status);
        if (Boolean.FALSE.equals(leave.getUser().getActive())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leave cannot be reviewed for a deactivated user");
        }
        if (status == LeaveStatus.APPROVED) {
            balanceService.requireAvailableForApproval(
                    leave.getUser(),
                    leave.getLeaveType(),
                    leave.getStartDate(),
                    leave.getEndDate()
            );
        }
        leave.setStatus(status);
        leave.setReviewer(currentUser);
        leave.setReviewerNote(normalize(reviewerNote));
        leave.setReviewedAt(businessClock.now());
        LeaveRequest saved = repository.saveAndFlush(leave);
        auditService.recordWithDetails(
                currentUser.getId(),
                leave.getUser().getId(),
                "LEAVE_REVIEWED",
                "SUCCESS",
                (currentUser.getRole() == Role.ADMIN
                        ? "ADMIN_ORGANIZATION_OVERRIDE_"
                        : "SCOPED_MANAGER_REVIEW_") + status.name(),
                "requestId=" + leave.getId(),
                leave.getUser().getEmail(),
                RequestMetadata.current()
        );
        notificationService.notifyUserDatabaseOnly(
                leave.getUser(),
                NotificationType.SYSTEM,
                "Leave request " + status.name().toLowerCase(),
                "Your leave request was " + status.name().toLowerCase() + ".",
                "/leave?request=" + leave.getId()
        );
        return map(saved, currentUser);
    }

    private LeaveRequest getRequest(Long requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));
    }

    private User getCurrentUser() {
        return securityUtils.getCurrentUser();
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End date cannot be before start date");
        }
    }

    private void validateLeaveType(LeaveType leaveType) {
        if (leaveType != LeaveType.ANNUAL && leaveType != LeaveType.SICK) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only annual and sick leave are available");
        }
    }

    private int requestedDays(LeaveRequest request) {
        return dayCalculator.count(request.getStartDate(), request.getEndDate());
    }

    private void recordEvent(User actor, User target, String eventType, String details) {
        auditService.recordWithDetails(
                actor.getId(),
                target.getId(),
                eventType,
                "SUCCESS",
                eventType,
                details,
                target.getEmail(),
                RequestMetadata.current()
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private LeaveRequestResponse map(LeaveRequest request, User viewer) {
        return new LeaveRequestResponse(
                request.getId(),
                request.getUser().getId(),
                request.getUser().getFullName(),
                request.getLeaveType(),
                request.getStatus(),
                request.getStartDate(),
                request.getEndDate(),
                requestedDays(request),
                request.getReason(),
                request.getReviewer() != null ? request.getReviewer().getFullName() : null,
                request.getReviewerNote(),
                request.getReviewedAt(),
                request.getCreatedAt(),
                request.getStatus() == LeaveStatus.PENDING
                        && authorizationPolicy.canReviewLeave(viewer, request)
        );
    }
}
