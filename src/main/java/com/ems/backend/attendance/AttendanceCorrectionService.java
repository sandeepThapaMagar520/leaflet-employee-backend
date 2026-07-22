package com.ems.backend.attendance;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;

import com.ems.backend.attendance.dto.AttendanceCorrectionResponse;
import com.ems.backend.attendance.dto.CreateAttendanceCorrectionRequest;
import com.ems.backend.attendance.dto.ReviewAttendanceCorrectionRequest;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.notification.EventIds;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.time.BusinessClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class AttendanceCorrectionService {
    private final AttendanceCorrectionRepository correctionRepository;
    private final AttendanceSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final AuthorizationPolicyService authorizationPolicy;
    private final SecurityAuditService auditService;
    private final BusinessClock businessClock;
    private final AttendanceCalculationService calculationService;
    private final Duration maxCorrectedSession;
    private final int correctionDeadlineDays;

    public AttendanceCorrectionService(
            AttendanceCorrectionRepository correctionRepository,
            AttendanceSessionRepository sessionRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            NotificationService notificationService,
            AuthorizationPolicyService authorizationPolicy,
            SecurityAuditService auditService,
            BusinessClock businessClock,
            AttendanceCalculationService calculationService,
            @Value("${app.policy.attendance.max-session-minutes:1440}") long maxSessionMinutes,
            @Value("${app.policy.attendance.correction-deadline-days:30}") int correctionDeadlineDays
    ) {
        this.correctionRepository = correctionRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.notificationService = notificationService;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.calculationService = calculationService;
        this.maxCorrectedSession = Duration.ofMinutes(maxSessionMinutes);
        this.correctionDeadlineDays = correctionDeadlineDays;
    }

    public AttendanceCorrectionResponse create(CreateAttendanceCorrectionRequest request) {
        User currentUser = securityUtils.getCurrentUser();
        userRepository.findByIdForUpdate(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Current user not found"));
        AttendanceSession session = sessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can correct only your own attendance sessions");
        }
        session = sessionRepository.findByIdForUpdate(session.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        if (session.getEndTime() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active session cannot be corrected");
        }
        if (session.getEndTime().isBefore(businessClock.now().minus(Duration.ofDays(correctionDeadlineDays)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The attendance correction deadline has passed");
        }
        validateTimes(request.requestedStartTime(), request.requestedEndTime());
        if (correctionRepository.existsByAttendanceSessionIdAndStatus(session.getId(), AttendanceCorrectionStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A correction for this session is already pending");
        }

        AttendanceCorrectionRequest correction = new AttendanceCorrectionRequest();
        correction.setAttendanceSession(session);
        correction.setUser(currentUser);
        correction.setOriginalStartTime(session.getStartTime());
        correction.setOriginalEndTime(session.getEndTime());
        correction.setRequestedStartTime(request.requestedStartTime());
        correction.setRequestedEndTime(request.requestedEndTime());
        correction.setReason(request.reason().trim());
        correction.setStatus(AttendanceCorrectionStatus.PENDING);
        AttendanceCorrectionRequest saved = correctionRepository.saveAndFlush(correction);
        auditService.recordWithDetails(
                currentUser.getId(),
                currentUser.getId(),
                "ATTENDANCE_CORRECTION_REQUESTED",
                "SUCCESS",
                "EMPLOYEE_CORRECTION_REQUEST",
                "correctionId=" + saved.getId() + ",sessionId=" + session.getId(),
                currentUser.getEmail(),
                RequestMetadata.current()
        );
        return map(saved, currentUser);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceCorrectionResponse> list(int page, int size) {
        User currentUser = securityUtils.getCurrentUser();
        var pageable = Pagination.page(page, size, "createdAt", "desc", java.util.Set.of("createdAt"));
        var requests = switch (currentUser.getRole()) {
            case ADMIN -> correctionRepository.findAllWithDetails(pageable);
            case MANAGER -> correctionRepository.findVisibleToManager(currentUser.getId(), pageable);
            case EMPLOYEE -> correctionRepository.findByUserId(currentUser.getId(), pageable);
        };
        return PageResponse.from(requests, request -> map(request, currentUser));
    }

    public AttendanceCorrectionResponse approve(Long correctionId, ReviewAttendanceCorrectionRequest request) {
        AttendanceCorrectionRequest correction = prepareReview(correctionId, request, AttendanceCorrectionStatus.APPROVED);
        userRepository.findByIdForUpdate(correction.getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance owner not found"));
        AttendanceSession session = sessionRepository.findByIdForUpdate(correction.getAttendanceSession().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        validateTimes(correction.getRequestedStartTime(), correction.getRequestedEndTime());
        if (!sessionRepository.findOverlappingForUpdate(
                correction.getUser().getId(),
                session.getId(),
                correction.getRequestedStartTime(),
                correction.getRequestedEndTime()
        ).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The corrected attendance time overlaps another session"
            );
        }

        long elapsedMinutes = Duration.between(
                correction.getRequestedStartTime(),
                correction.getRequestedEndTime()
        ).toMinutes();
        long breakMinutes = effectiveBreakMinutes(session, correction.getRequestedEndTime());
        if (breakMinutes > elapsedMinutes) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The existing break duration exceeds the corrected session duration"
            );
        }
        session.setStartTime(correction.getRequestedStartTime());
        session.setEndTime(correction.getRequestedEndTime());
        session.setBreakMinutes(Math.toIntExact(breakMinutes));
        session.setBreakStartedAt(null);
        session.setTotalHours(calculationService.hours(elapsedMinutes - breakMinutes));
        sessionRepository.saveAndFlush(session);
        AttendanceCorrectionRequest saved = correctionRepository.saveAndFlush(correction);
        auditReview(saved, AttendanceCorrectionStatus.APPROVED,
                """
                {"correctionId":%d,"sessionId":%d,"originalStart":"%s","originalEnd":"%s",\
                "resultStart":"%s","resultEnd":"%s","breakMinutes":%d,"netMinutes":%d}
                """.formatted(
                        saved.getId(),
                        session.getId(),
                        saved.getOriginalStartTime(),
                        saved.getOriginalEndTime(),
                        session.getStartTime(),
                        session.getEndTime(),
                        breakMinutes,
                        elapsedMinutes - breakMinutes
                ).strip());
        notifyEmployee(saved, "Attendance correction approved", "Your attendance correction was approved.");
        return map(saved, securityUtils.getCurrentUser());
    }

    public AttendanceCorrectionResponse reject(Long correctionId, ReviewAttendanceCorrectionRequest request) {
        AttendanceCorrectionRequest correction = prepareReview(correctionId, request, AttendanceCorrectionStatus.REJECTED);
        AttendanceCorrectionRequest saved = correctionRepository.saveAndFlush(correction);
        auditReview(saved, AttendanceCorrectionStatus.REJECTED,
                "{\"correctionId\":%d,\"sessionId\":%d}".formatted(
                        saved.getId(),
                        saved.getAttendanceSession().getId()
                ));
        notifyEmployee(saved, "Attendance correction rejected", "Your attendance correction was not approved.");
        return map(saved, securityUtils.getCurrentUser());
    }

    private AttendanceCorrectionRequest prepareReview(
            Long correctionId,
            ReviewAttendanceCorrectionRequest request,
            AttendanceCorrectionStatus status
    ) {
        User reviewer = securityUtils.getCurrentUser();
        AttendanceCorrectionRequest correction = correctionRepository.findByIdForUpdate(correctionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance correction not found"));
        authorizationPolicy.requireReviewAttendanceCorrection(reviewer, correction);
        if (correction.getStatus() != AttendanceCorrectionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending corrections can be reviewed");
        }
        correction.setStatus(status);
        correction.setReviewer(reviewer);
        correction.setReviewerNote(request.reviewerNote());
        correction.setReviewedAt(businessClock.now());
        return correction;
    }

    private void auditReview(
            AttendanceCorrectionRequest correction,
            AttendanceCorrectionStatus status,
            String resultDetails
    ) {
        User reviewer = correction.getReviewer();
        auditService.recordWithDetails(
                reviewer.getId(),
                correction.getUser().getId(),
                "ATTENDANCE_CORRECTION_REVIEWED",
                "SUCCESS",
                (reviewer.getRole() == Role.ADMIN
                        ? "ADMIN_ORGANIZATION_OVERRIDE_"
                        : "SCOPED_MANAGER_REVIEW_") + status.name(),
                resultDetails,
                correction.getUser().getEmail(),
                RequestMetadata.current()
        );
    }

    private long effectiveBreakMinutes(AttendanceSession session, Instant correctedEnd) {
        long savedMinutes = session.getBreakMinutes() != null ? session.getBreakMinutes() : 0;
        if (session.getBreakStartedAt() == null) return savedMinutes;
        if (correctedEnd.isBefore(session.getBreakStartedAt())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The corrected end time is before the active break began"
            );
        }
        return savedMinutes + Duration.between(session.getBreakStartedAt(), correctedEnd).toMinutes();
    }

    private void validateTimes(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        if (duration.isNegative() || duration.isZero()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time");
        }
        if (duration.compareTo(maxCorrectedSession) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A corrected session cannot exceed 24 hours");
        }
        if (end.isAfter(businessClock.now().plus(Duration.ofMinutes(5)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corrected attendance cannot end in the future");
        }
    }

    private void notifyEmployee(AttendanceCorrectionRequest correction, String title, String message) {
        notificationService.notifyUserEvent(
                EventIds.stable("ATTENDANCE_CORRECTION_REVIEWED", correction.getId(), correction.getStatus(), correction.getVersion()),
                "ATTENDANCE_CORRECTION_REVIEWED",
                correction.getUser(),
                NotificationType.SYSTEM,
                title,
                message,
                "/attendance?correction=" + correction.getId(),
                true
        );
    }

    private AttendanceCorrectionResponse map(
            AttendanceCorrectionRequest correction,
            User viewer
    ) {
        return new AttendanceCorrectionResponse(
                correction.getId(),
                correction.getAttendanceSession().getId(),
                correction.getUser().getId(),
                correction.getUser().getFullName(),
                correction.getOriginalStartTime(),
                correction.getOriginalEndTime(),
                correction.getRequestedStartTime(),
                correction.getRequestedEndTime(),
                correction.getReason(),
                correction.getStatus(),
                correction.getReviewer() != null ? correction.getReviewer().getFullName() : null,
                correction.getReviewerNote(),
                correction.getReviewedAt(),
                correction.getCreatedAt(),
                correction.getStatus() == AttendanceCorrectionStatus.PENDING
                        && viewer.getRole() != Role.EMPLOYEE
                        && !viewer.getId().equals(correction.getUser().getId())
        );
    }
}
