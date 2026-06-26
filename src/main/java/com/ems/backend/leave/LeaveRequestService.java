package com.ems.backend.leave;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.leave.dto.CreateLeaveRequest;
import com.ems.backend.leave.dto.LeaveBalanceResponse;
import com.ems.backend.leave.dto.LeaveRequestResponse;
import com.ems.backend.leave.dto.UpdateLeaveBalanceRequest;
import com.ems.backend.leave.dto.UpdateLeaveStatusRequest;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class LeaveRequestService {
    private static final int ANNUAL_ALLOWANCE_DAYS = 20;

    private final LeaveRequestRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    public LeaveRequestService(LeaveRequestRepository repository, UserRepository userRepository, SecurityUtils securityUtils) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
    }

    public LeaveRequestResponse createRequest(CreateLeaveRequest request) {
        validateDates(request.startDate(), request.endDate());
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins can review leave requests but cannot submit them.");
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setUser(currentUser);
        leave.setLeaveType(request.leaveType());
        leave.setStartDate(request.startDate());
        leave.setEndDate(request.endDate());
        leave.setReason(request.reason());
        leave.setStatus(LeaveStatus.PENDING);
        return map(repository.save(leave));
    }

    public List<LeaveRequestResponse> listRequests() {
        User currentUser = getCurrentUser();
        List<LeaveRequest> requests = canReview(currentUser)
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByUserEmailIgnoreCaseOrderByCreatedAtDesc(currentUser.getEmail());
        return requests.stream().map(this::map).toList();
    }

    public LeaveBalanceResponse getMyBalance() {
        User currentUser = getCurrentUser();
        return balanceFor(currentUser);
    }

    public LeaveBalanceResponse getBalanceForUser(Long userId) {
        User currentUser = getCurrentUser();
        if (!canReview(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and managers can view staff leave balances");
        }
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return balanceFor(targetUser);
    }

    public LeaveBalanceResponse updateUserBalance(Long userId, UpdateLeaveBalanceRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can update leave balances");
        }
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        int approvedDays = approvedAnnualDays(targetUser);
        targetUser.setLeaveBalanceAdjustmentDays(request.remainingDays() + approvedDays - ANNUAL_ALLOWANCE_DAYS);
        User saved = userRepository.save(targetUser);
        return balanceFor(saved);
    }

    private LeaveBalanceResponse balanceFor(User user) {
        int approvedDays = approvedAnnualDays(user);
        int annualAllowance = annualAllowance(user);
        return new LeaveBalanceResponse(
                annualAllowance,
                approvedDays,
                Math.max(annualAllowance - approvedDays, 0)
        );
    }

    private int approvedAnnualDays(User user) {
        int currentYear = LocalDate.now().getYear();
        return repository.findByUserIdAndStatus(user.getId(), LeaveStatus.APPROVED).stream()
                .filter(request -> request.getLeaveType() == LeaveType.ANNUAL)
                .filter(request -> request.getStartDate().getYear() == currentYear || request.getEndDate().getYear() == currentYear)
                .mapToInt(this::requestedDays)
                .sum();
    }

    private int annualAllowance(User user) {
        return Math.max(ANNUAL_ALLOWANCE_DAYS + (user.getLeaveBalanceAdjustmentDays() != null ? user.getLeaveBalanceAdjustmentDays() : 0), 0);
    }

    public LeaveRequestResponse approve(Long requestId, UpdateLeaveStatusRequest request) {
        return review(requestId, request.reviewerNote(), LeaveStatus.APPROVED);
    }

    public LeaveRequestResponse reject(Long requestId, UpdateLeaveStatusRequest request) {
        return review(requestId, request.reviewerNote(), LeaveStatus.REJECTED);
    }

    public LeaveRequestResponse cancel(Long requestId) {
        User currentUser = getCurrentUser();
        LeaveRequest leave = getRequest(requestId);
        if (!leave.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can cancel only your own leave requests");
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only pending leave requests can be cancelled");
        }
        leave.setStatus(LeaveStatus.CANCELLED);
        return map(repository.save(leave));
    }

    private LeaveRequestResponse review(Long requestId, String reviewerNote, LeaveStatus status) {
        User currentUser = getCurrentUser();
        if (!canReview(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and managers can review leave requests");
        }
        LeaveRequest leave = getRequest(requestId);
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only pending leave requests can be reviewed");
        }
        leave.setStatus(status);
        leave.setReviewer(currentUser);
        leave.setReviewerNote(reviewerNote);
        leave.setReviewedAt(Instant.now());
        return map(repository.save(leave));
    }

    private LeaveRequest getRequest(Long requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));
    }

    private User getCurrentUser() {
        return securityUtils.getCurrentUser();
    }

    private boolean canReview(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.MANAGER;
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End date cannot be before start date");
        }
    }

    private int requestedDays(LeaveRequest request) {
        return (int) ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
    }

    private LeaveRequestResponse map(LeaveRequest request) {
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
                request.getCreatedAt()
        );
    }
}
