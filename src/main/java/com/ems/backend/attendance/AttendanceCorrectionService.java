package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceCorrectionResponse;
import com.ems.backend.attendance.dto.CreateAttendanceCorrectionRequest;
import com.ems.backend.attendance.dto.ReviewAttendanceCorrectionRequest;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class AttendanceCorrectionService {
    private static final Duration MAX_CORRECTED_SESSION = Duration.ofHours(24);

    private final AttendanceCorrectionRepository correctionRepository;
    private final AttendanceSessionRepository sessionRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;

    public AttendanceCorrectionService(
            AttendanceCorrectionRepository correctionRepository,
            AttendanceSessionRepository sessionRepository,
            SecurityUtils securityUtils,
            NotificationService notificationService
    ) {
        this.correctionRepository = correctionRepository;
        this.sessionRepository = sessionRepository;
        this.securityUtils = securityUtils;
        this.notificationService = notificationService;
    }

    public AttendanceCorrectionResponse create(CreateAttendanceCorrectionRequest request) {
        User currentUser = securityUtils.getCurrentUser();
        AttendanceSession session = sessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can correct only your own attendance sessions");
        }
        validateTimes(request.requestedStartTime(), request.requestedEndTime());
        if (correctionRepository.existsByAttendanceSessionIdAndStatus(session.getId(), AttendanceCorrectionStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A correction for this session is already pending");
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
        AttendanceCorrectionRequest saved = correctionRepository.save(correction);
        return map(saved);
    }

    @Transactional(readOnly = true)
    public List<AttendanceCorrectionResponse> list() {
        User currentUser = securityUtils.getCurrentUser();
        List<AttendanceCorrectionRequest> requests = canReview(currentUser)
                ? correctionRepository.findAllByOrderByCreatedAtDesc()
                : correctionRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId());
        return requests.stream().map(this::map).toList();
    }

    public AttendanceCorrectionResponse approve(Long correctionId, ReviewAttendanceCorrectionRequest request) {
        AttendanceCorrectionRequest correction = prepareReview(correctionId, request, AttendanceCorrectionStatus.APPROVED);
        AttendanceSession session = correction.getAttendanceSession();
        session.setStartTime(correction.getRequestedStartTime());
        session.setEndTime(correction.getRequestedEndTime());
        session.setTotalHours(toHours(Duration.between(session.getStartTime(), session.getEndTime())));
        sessionRepository.save(session);
        AttendanceCorrectionRequest saved = correctionRepository.save(correction);
        notifyEmployee(saved, "Attendance correction approved", "Your attendance correction was approved.");
        return map(saved);
    }

    public AttendanceCorrectionResponse reject(Long correctionId, ReviewAttendanceCorrectionRequest request) {
        AttendanceCorrectionRequest correction = prepareReview(correctionId, request, AttendanceCorrectionStatus.REJECTED);
        AttendanceCorrectionRequest saved = correctionRepository.save(correction);
        notifyEmployee(saved, "Attendance correction rejected", "Your attendance correction was not approved.");
        return map(saved);
    }

    private AttendanceCorrectionRequest prepareReview(
            Long correctionId,
            ReviewAttendanceCorrectionRequest request,
            AttendanceCorrectionStatus status
    ) {
        User reviewer = securityUtils.getCurrentUser();
        if (!canReview(reviewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and managers can review corrections");
        }
        AttendanceCorrectionRequest correction = correctionRepository.findById(correctionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance correction not found"));
        if (correction.getStatus() != AttendanceCorrectionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only pending corrections can be reviewed");
        }
        correction.setStatus(status);
        correction.setReviewer(reviewer);
        correction.setReviewerNote(request.reviewerNote());
        correction.setReviewedAt(Instant.now());
        return correction;
    }

    private void validateTimes(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        if (duration.isNegative() || duration.isZero()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time");
        }
        if (duration.compareTo(MAX_CORRECTED_SESSION) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A corrected session cannot exceed 24 hours");
        }
        if (end.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corrected attendance cannot end in the future");
        }
    }

    private boolean canReview(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.MANAGER;
    }

    private BigDecimal toHours(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis() / 3_600_000.0).setScale(2, RoundingMode.HALF_UP);
    }

    private void notifyEmployee(AttendanceCorrectionRequest correction, String title, String message) {
        notificationService.notifyUser(
                correction.getUser(),
                NotificationType.SYSTEM,
                title,
                message,
                "/attendance?correction=" + correction.getId()
        );
    }

    private AttendanceCorrectionResponse map(AttendanceCorrectionRequest correction) {
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
                correction.getCreatedAt()
        );
    }
}
