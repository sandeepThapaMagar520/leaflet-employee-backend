package com.ems.backend.attendance;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;

import com.ems.backend.attendance.dto.AttendanceDaySummaryResponse;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.leave.LeaveRequestRepository;
import com.ems.backend.leave.LeaveStatus;
import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.time.BusinessClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class AttendanceSessionService {
    private final AttendanceSessionRepository repository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AppSettingsService settingsService;
    private final SecurityUtils securityUtils;
    private final BusinessClock businessClock;
    private final AttendanceCalculationService calculationService;
    private final AuthorizationPolicyService authorizationPolicy;
    private final SecurityAuditService auditService;
    private final long maxSessionMinutes;

    public AttendanceSessionService(
            AttendanceSessionRepository repository,
            UserRepository userRepository,
            LeaveRequestRepository leaveRequestRepository,
            AppSettingsService settingsService,
            SecurityUtils securityUtils,
            AuthorizationPolicyService authorizationPolicy,
            SecurityAuditService auditService,
            BusinessClock businessClock,
            AttendanceCalculationService calculationService,
            @Value("${app.policy.attendance.max-session-minutes:1440}") long maxSessionMinutes
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.settingsService = settingsService;
        this.securityUtils = securityUtils;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.calculationService = calculationService;
        this.maxSessionMinutes = maxSessionMinutes;
    }

    public AttendanceSessionResponse startSession() {
        User currentUser = getCurrentUser();
        LocalDate today = businessClock.today();
        if (isOnApprovedLeave(currentUser.getId(), today)) {
            throw new ResponseStatusException(BAD_REQUEST, "You are on approved leave today. Ask an admin to start attendance if work is required.");
        }

        AttendanceSessionResponse response = startSessionForUser(currentUser);
        auditAttendanceEvent(currentUser, "ATTENDANCE_SESSION_STARTED", "SELF_SERVICE");
        return response;
    }

    public AttendanceSessionResponse startUserActiveSession(Long userId, String reason) {
        User currentUser = getCurrentUser();
        if (!settingsService.attendanceAdminOverrideEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance overrides are disabled in settings.");
        }
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        authorizationPolicy.requireManageAttendance(currentUser, targetUser);
        if (targetUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance sessions cannot be started from Team Attendance.");
        }
        if (Boolean.FALSE.equals(targetUser.getActive())) {
            throw new ResponseStatusException(CONFLICT, "Attendance cannot be started for a deactivated user.");
        }
        AttendanceSessionResponse response = startSessionForUser(targetUser);
        auditAttendanceOverride(currentUser, targetUser, "ATTENDANCE_SESSION_STARTED", reason);
        return response;
    }

    public AttendanceSessionResponse endSession() {
        User currentUser = getCurrentUser();

        AttendanceSession activeSession = repository.findActiveByUserIdForUpdate(currentUser.getId())
                .orElse(null);
        if (activeSession == null) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found to end.");
        }

        AttendanceSessionResponse response = closeSession(activeSession, businessClock.now(), false);
        auditAttendanceEvent(currentUser, "ATTENDANCE_SESSION_ENDED", "SELF_SERVICE");
        return response;
    }

    public AttendanceSessionResponse heartbeat() {
        AttendanceSession session = getCurrentUserActiveSession();
        session.setLastHeartbeatAt(businessClock.now());
        return map(repository.save(session));
    }

    public AttendanceSessionResponse startBreak() {
        AttendanceSession session = getCurrentUserActiveSession();
        if (session.getBreakStartedAt() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "You are already on break.");
        }
        Instant now = businessClock.now();
        session.setBreakStartedAt(now);
        session.setLastHeartbeatAt(now);
        AttendanceSessionResponse response = map(repository.save(session));
        auditAttendanceEvent(session.getUser(), "ATTENDANCE_BREAK_STARTED", "SELF_SERVICE");
        return response;
    }

    public AttendanceSessionResponse endBreak() {
        AttendanceSession session = getCurrentUserActiveSession();
        if (session.getBreakStartedAt() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "No active break found.");
        }
        Instant now = businessClock.now();
        session.setBreakMinutes(Math.toIntExact(calculationService.breakMinutes(session, now)));
        session.setBreakStartedAt(null);
        session.setLastHeartbeatAt(now);
        AttendanceSessionResponse response = map(repository.save(session));
        auditAttendanceEvent(session.getUser(), "ATTENDANCE_BREAK_ENDED", "SELF_SERVICE");
        return response;
    }

    public AttendanceSessionResponse endUserActiveSession(Long userId, String reason) {
        User currentUser = getCurrentUser();
        if (!settingsService.attendanceAdminOverrideEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance overrides are disabled in settings.");
        }

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        authorizationPolicy.requireManageAttendance(currentUser, targetUser);
        if (targetUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance sessions cannot be closed from Team Attendance.");
        }

        AttendanceSession activeSession = repository.findActiveByUserIdForUpdate(targetUser.getId())
                .orElse(null);
        if (activeSession == null) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found for this team member.");
        }

        AttendanceSessionResponse response = closeSession(activeSession, businessClock.now(), true);
        auditAttendanceOverride(currentUser, targetUser, "ATTENDANCE_SESSION_ENDED", reason);
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSessionResponse> getMySessions(int page, int size) {
        Long userId = getCurrentUser().getId();
        var pageable = Pagination.page(page, size, "startTime", "desc", java.util.Set.of("startTime"));
        return PageResponse.from(repository.findByUserId(userId, pageable), this::map);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSessionResponse> getAllSessions(int page, int size) {
        User currentUser = getCurrentUser();
        var pageable = Pagination.page(page, size, "startTime", "desc", java.util.Set.of("startTime"));
        var sessions = switch (currentUser.getRole()) {
            case ADMIN -> repository.findAllNonAdmin(pageable);
            case MANAGER -> repository.findVisibleToManager(currentUser.getId(), pageable);
            case EMPLOYEE -> repository.findByUserId(currentUser.getId(), pageable);
        };
        return PageResponse.from(sessions, this::map);
    }

    public List<AttendanceSessionResponse> getSessionsByUser(Long userId) {
        User currentUser = getCurrentUser();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        authorizationPolicy.requireViewAttendance(currentUser, target);
        return repository.findByUserIdOrderByStartTimeDesc(userId).stream().map(this::map).toList();
    }

    public AttendanceDaySummaryResponse getMyTodaySummary() {
        User currentUser = getCurrentUser();
        LocalDate today = businessClock.today();
        return buildSummary(currentUser, today, businessClock.now(), isOnApprovedLeave(currentUser.getId(), today));
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceDaySummaryResponse> getTeamDailySummary(LocalDate date, int page, int size) {
        LocalDate workDate = date != null ? date : businessClock.today();
        Instant from = businessClock.startOfDay(workDate);
        Instant to = businessClock.startOfDay(workDate.plusDays(1));
        Instant now = businessClock.now();

        User currentUser = getCurrentUser();
        var pageable = Pagination.page(page, size, "fullName", "asc", java.util.Set.of("fullName"));
        var visiblePage = switch (currentUser.getRole()) {
            case ADMIN -> userRepository.findAllActiveNonAdmin(pageable);
            case MANAGER -> userRepository.findActiveManagedEmployees(currentUser.getId(), pageable);
            case EMPLOYEE -> new org.springframework.data.domain.PageImpl<>(List.of(currentUser), pageable, 1);
        };
        List<User> visibleUsers = visiblePage.getContent();
        List<Long> visibleUserIds = visibleUsers.stream().map(User::getId).toList();
        Map<Long, List<AttendanceSession>> sessionsByUser = visibleUserIds.isEmpty()
                ? Map.of()
                : repository.findSessionsOverlappingDayForUsers(visibleUserIds, from, to)
                        .stream()
                        .collect(Collectors.groupingBy(session -> session.getUser().getId()));
        Set<Long> usersOnLeave = visibleUserIds.isEmpty()
                ? Set.of()
                : leaveRequestRepository
                        .findOverlappingForUsers(
                                LeaveStatus.APPROVED, workDate, workDate, visibleUserIds
                        )
                        .stream()
                        .map(leave -> leave.getUser().getId())
                        .collect(Collectors.toSet());

        List<AttendanceDaySummaryResponse> content = visibleUsers.stream()
                .map(user -> buildSummary(
                        user,
                        workDate,
                        sessionsByUser.getOrDefault(user.getId(), List.of()),
                        now,
                        usersOnLeave.contains(user.getId())
                ))
                .toList();
        return PageResponse.from(new org.springframework.data.domain.PageImpl<>(
                content, pageable, visiblePage.getTotalElements()));
    }
    
    public AttendanceSessionResponse getActiveSession() {
        User currentUser = getCurrentUser();
        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (activeSessions.isEmpty()) {
            return null;
        }
        return map(activeSessions.getFirst());
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSessionResponse> getSessionsForExport(
            LocalDate fromDate, LocalDate toDate, int page, int size
    ) {
        User currentUser = getCurrentUser();
        Instant from = businessClock.startOfDay(fromDate);
        Instant to = businessClock.startOfDay(toDate.plusDays(1));
        var pageable = Pagination.page(page, size, "startTime", "desc", java.util.Set.of("startTime"));
        var sessions = switch (currentUser.getRole()) {
            case ADMIN -> repository.findAllNonAdminBetween(from, to, pageable);
            case MANAGER -> repository.findVisibleToManagerBetween(currentUser.getId(), from, to, pageable);
            case EMPLOYEE -> repository.findByUserIdBetween(currentUser.getId(), from, to, pageable);
        };
        return PageResponse.from(sessions, this::map);
    }

    private AttendanceDaySummaryResponse buildSummary(User user, LocalDate workDate, Instant now, boolean onLeave) {
        Instant from = businessClock.startOfDay(workDate);
        Instant to = businessClock.startOfDay(workDate.plusDays(1));
        List<AttendanceSession> sessions = repository
                .findUserSessionsOverlappingDay(user.getId(), from, to);
        return buildSummary(user, workDate, sessions, now, onLeave);
    }

    private AttendanceDaySummaryResponse buildSummary(
            User user,
            LocalDate workDate,
            List<AttendanceSession> sessions,
            Instant now,
            boolean onLeave
    ) {
        Instant from = businessClock.startOfDay(workDate);
        Instant to = businessClock.startOfDay(workDate.plusDays(1));
        Instant firstStart = sessions.stream()
                .map(AttendanceSession::getStartTime)
                .map(start -> start.isBefore(from) ? from : start)
                .min(Instant::compareTo)
                .orElse(null);
        Instant lastEnd = sessions.stream()
                .map(AttendanceSession::getEndTime)
                .filter(endTime -> endTime != null)
                .map(endTime -> endTime.isAfter(to) ? to : endTime)
                .max(Instant::compareTo)
                .orElse(null);
        Instant activeStart = sessions.stream()
                .filter(session -> session.getEndTime() == null)
                .map(AttendanceSession::getStartTime)
                .map(start -> start.isBefore(from) ? from : start)
                .findFirst()
                .orElse(null);
        AttendanceSession activeSession = sessions.stream()
                .filter(session -> session.getEndTime() == null)
                .findFirst()
                .orElse(null);
        Instant activeBreakStart = activeSession != null ? activeSession.getBreakStartedAt() : null;
        Instant activeLastHeartbeat = activeSession != null ? activeSession.getLastHeartbeatAt() : null;

        long totalMinutes = sessions.stream()
                .mapToLong(session -> calculationService.netMinutes(session, from, to, now))
                .sum();
        long requiredMinutes = settingsService.attendanceRequiredMinutes();
        long graceMinutes = settingsService.attendanceGraceMinutes();
        long remainingMinutes = Math.max(requiredMinutes - totalMinutes, 0);

        return new AttendanceDaySummaryResponse(
                user.getId(),
                user.getFullName(),
                workDate,
                firstStart,
                lastEnd,
                activeStart,
                totalMinutes,
                requiredMinutes,
                graceMinutes,
                remainingMinutes,
                Math.max(totalMinutes - requiredMinutes, 0),
                Math.max(requiredMinutes - totalMinutes, 0),
                resolveStatus(workDate, totalMinutes, activeStart, activeBreakStart, activeLastHeartbeat, sessions.isEmpty(), onLeave, now)
        );
    }

    private AttendanceDayStatus resolveStatus(
            LocalDate workDate,
            long totalMinutes,
            Instant activeStart,
            Instant activeBreakStart,
            Instant activeLastHeartbeat,
            boolean noSessions,
            boolean onLeave,
            Instant now
    ) {
        if (onLeave && noSessions) {
            return AttendanceDayStatus.ON_LEAVE;
        }
        if (activeStart != null && isHeartbeatStale(activeLastHeartbeat, now)) {
            return AttendanceDayStatus.MISSING_CHECKOUT;
        }
        if (activeStart != null && isStaleSession(workDate, activeStart, now)) {
            return AttendanceDayStatus.MISSING_CHECKOUT;
        }
        if (onLeave && !noSessions) {
            return AttendanceDayStatus.WORKED_ON_LEAVE;
        }
        if (activeBreakStart != null) {
            return AttendanceDayStatus.ON_BREAK;
        }
        if (activeStart != null) {
            return AttendanceDayStatus.IN_PROGRESS;
        }
        if (noSessions) {
            return AttendanceDayStatus.NO_ACTIVITY;
        }
        if (totalMinutes >= settingsService.attendanceRequiredMinutes()) {
            return totalMinutes > settingsService.attendanceRequiredMinutes()
                    ? AttendanceDayStatus.OVERTIME
                    : AttendanceDayStatus.COMPLETED;
        }
        if (totalMinutes >= settingsService.attendanceGraceMinutes()) {
            return AttendanceDayStatus.COMPLETED_WITH_GRACE;
        }
        return AttendanceDayStatus.UNDER_HOURS;
    }

    private boolean isStaleSession(LocalDate workDate, Instant activeStart, Instant now) {
        LocalDate today = businessClock.today();
        return workDate.isBefore(today) || Duration.between(activeStart, now).toMinutes() >= settingsService.attendanceMissingCheckoutMinutes();
    }

    private boolean isHeartbeatStale(Instant lastHeartbeatAt, Instant now) {
        return lastHeartbeatAt != null && Duration.between(lastHeartbeatAt, now).toMinutes() >= settingsService.attendanceHeartbeatStaleMinutes();
    }

    private boolean isOnApprovedLeave(Long userId, LocalDate date) {
        return leaveRequestRepository
                .existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        userId,
                        LeaveStatus.APPROVED,
                        date,
                        date
                );
    }

    private AttendanceSessionResponse startSessionForUser(User user) {
        User lockedUser = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(user.getId());
        if (!activeSessions.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "This user already has an active session.");
        }

        AttendanceSession session = new AttendanceSession();
        session.setUser(lockedUser);
        Instant now = businessClock.now();
        session.setStartTime(now);
        session.setLastHeartbeatAt(now);

        return map(repository.saveAndFlush(session));
    }

    private AttendanceSessionResponse closeSession(AttendanceSession session, Instant endTime, boolean override) {
        long elapsed = Duration.between(session.getStartTime(), endTime).toMinutes();
        if (!override && elapsed > maxSessionMinutes) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Session exceeds the maximum duration and requires an authorized override"
            );
        }
        session.setEndTime(endTime);
        if (session.getBreakStartedAt() != null) {
            session.setBreakMinutes(Math.toIntExact(calculationService.breakMinutes(session, endTime)));
            session.setBreakStartedAt(null);
        }
        long workedMinutes = calculationService.netMinutes(session, session.getStartTime(), endTime, endTime);
        session.setTotalHours(calculationService.hours(workedMinutes));
        return map(repository.save(session));
    }

    private AttendanceSession getCurrentUserActiveSession() {
        User currentUser = getCurrentUser();
        return repository.findActiveByUserIdForUpdate(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "No active session found."));
    }

    private User getCurrentUser() {
        String email = securityUtils.getCurrentUserEmail();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Current user not found"));
    }

    private void auditAttendanceOverride(User actor, User target, String eventType, String reason) {
        auditService.recordWithDetails(
                actor.getId(),
                target.getId(),
                eventType,
                "SUCCESS",
                actor.getRole() == Role.ADMIN
                        ? "ADMIN_ORGANIZATION_OVERRIDE"
                        : "SCOPED_MANAGER_OVERRIDE",
                "reason=" + reason.trim(),
                target.getEmail(),
                RequestMetadata.current()
        );
    }

    private void auditAttendanceEvent(User user, String eventType, String reason) {
        auditService.record(
                user.getId(),
                user.getId(),
                eventType,
                "SUCCESS",
                reason,
                user.getEmail(),
                RequestMetadata.current()
        );
    }

    private AttendanceSessionResponse map(AttendanceSession session) {
        return new AttendanceSessionResponse(
                session.getId(),
                session.getUser().getId(),
                session.getUser().getFullName(),
                session.getStartTime(),
                session.getEndTime(),
                session.getTotalHours(),
                session.getLastHeartbeatAt(),
                session.getBreakStartedAt(),
                session.getBreakMinutes()
        );
    }
}
