package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceDaySummaryResponse;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.leave.LeaveRequestRepository;
import com.ems.backend.leave.LeaveStatus;
import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class AttendanceSessionService {
    private final AttendanceSessionRepository repository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AppSettingsService settingsService;
    private final SecurityUtils securityUtils;
    private final ZoneId attendanceZone;

    public AttendanceSessionService(
            AttendanceSessionRepository repository,
            UserRepository userRepository,
            LeaveRequestRepository leaveRequestRepository,
            AppSettingsService settingsService,
            SecurityUtils securityUtils,
            @Value("${app.attendance.zone-id:Asia/Kathmandu}") String attendanceZoneId
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.settingsService = settingsService;
        this.securityUtils = securityUtils;
        this.attendanceZone = ZoneId.of(attendanceZoneId);
    }

    public AttendanceSessionResponse startSession() {
        User currentUser = getCurrentUser();
        LocalDate today = LocalDate.now(attendanceZone);
        if (isOnApprovedLeave(currentUser.getId(), today)) {
            throw new ResponseStatusException(BAD_REQUEST, "You are on approved leave today. Ask an admin to start attendance if work is required.");
        }

        return startSessionForUser(currentUser);
    }

    public AttendanceSessionResponse startUserActiveSession(Long userId) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(FORBIDDEN, "Only admins and managers can start team sessions.");
        }
        if (!settingsService.attendanceAdminOverrideEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance overrides are disabled in settings.");
        }
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (targetUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance sessions cannot be started from Team Attendance.");
        }
        return startSessionForUser(targetUser);
    }

    public AttendanceSessionResponse endSession() {
        User currentUser = getCurrentUser();

        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found to end.");
        }

        return closeSession(activeSessions.getFirst(), Instant.now());
    }

    public AttendanceSessionResponse heartbeat() {
        AttendanceSession session = getCurrentUserActiveSession();
        session.setLastHeartbeatAt(Instant.now());
        return map(repository.save(session));
    }

    public AttendanceSessionResponse startBreak() {
        AttendanceSession session = getCurrentUserActiveSession();
        if (session.getBreakStartedAt() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "You are already on break.");
        }
        Instant now = Instant.now();
        session.setBreakStartedAt(now);
        session.setLastHeartbeatAt(now);
        return map(repository.save(session));
    }

    public AttendanceSessionResponse endBreak() {
        AttendanceSession session = getCurrentUserActiveSession();
        if (session.getBreakStartedAt() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "No active break found.");
        }
        Instant now = Instant.now();
        session.setBreakMinutes(Math.toIntExact(totalBreakMinutes(session, now)));
        session.setBreakStartedAt(null);
        session.setLastHeartbeatAt(now);
        return map(repository.save(session));
    }

    public AttendanceSessionResponse endUserActiveSession(Long userId) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(FORBIDDEN, "Only admins and managers can close team sessions.");
        }
        if (!settingsService.attendanceAdminOverrideEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance overrides are disabled in settings.");
        }

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (targetUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Admin attendance sessions cannot be closed from Team Attendance.");
        }

        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(targetUser.getId());
        if (activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found for this team member.");
        }

        return closeSession(activeSessions.getFirst(), Instant.now());
    }

    public List<AttendanceSessionResponse> getMySessions() {
        String email = securityUtils.getCurrentUserEmail();
        return repository.findByUserEmailIgnoreCaseOrderByStartTimeDesc(email).stream().map(this::map).toList();
    }

    public List<AttendanceSessionResponse> getAllSessions() {
        return repository.findAllByOrderByStartTimeDesc().stream()
                .filter(session -> session.getUser().getRole() != Role.ADMIN)
                .map(this::map)
                .toList();
    }

    public List<AttendanceSessionResponse> getSessionsByUser(Long userId) {
        return repository.findByUserIdOrderByStartTimeDesc(userId).stream().map(this::map).toList();
    }

    public AttendanceDaySummaryResponse getMyTodaySummary() {
        User currentUser = getCurrentUser();
        LocalDate today = LocalDate.now(attendanceZone);
        return buildSummary(currentUser, today, Instant.now(), isOnApprovedLeave(currentUser.getId(), today));
    }

    public List<AttendanceDaySummaryResponse> getTeamDailySummary(LocalDate date) {
        LocalDate workDate = date != null ? date : LocalDate.now(attendanceZone);
        Instant from = workDate.atStartOfDay(attendanceZone).toInstant();
        Instant to = workDate.plusDays(1).atStartOfDay(attendanceZone).toInstant();
        Instant now = Instant.now();

        Map<Long, List<AttendanceSession>> sessionsByUser = repository
                .findSessionsOverlappingDay(from, to)
                .stream()
                .collect(Collectors.groupingBy(session -> session.getUser().getId()));
        Set<Long> usersOnLeave = leaveRequestRepository
                .findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(LeaveStatus.APPROVED, workDate, workDate)
                .stream()
                .map(leave -> leave.getUser().getId())
                .collect(Collectors.toSet());

        return userRepository.findAll().stream()
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> user.getRole() != Role.ADMIN)
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> buildSummary(
                        user,
                        workDate,
                        sessionsByUser.getOrDefault(user.getId(), List.of()),
                        now,
                        usersOnLeave.contains(user.getId())
                ))
                .toList();
    }
    
    public AttendanceSessionResponse getActiveSession() {
        User currentUser = getCurrentUser();
        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (activeSessions.isEmpty()) {
            return null;
        }
        return map(activeSessions.getFirst());
    }

    public List<AttendanceSessionResponse> getSessionsForExport() {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.MANAGER) {
            return getAllSessions();
        }
        return getMySessions();
    }

    private AttendanceDaySummaryResponse buildSummary(User user, LocalDate workDate, Instant now, boolean onLeave) {
        Instant from = workDate.atStartOfDay(attendanceZone).toInstant();
        Instant to = workDate.plusDays(1).atStartOfDay(attendanceZone).toInstant();
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
        Instant from = workDate.atStartOfDay(attendanceZone).toInstant();
        Instant to = workDate.plusDays(1).atStartOfDay(attendanceZone).toInstant();
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
                .mapToLong(session -> sessionMinutes(session, from, to, now))
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
                resolveStatus(workDate, totalMinutes, activeStart, activeBreakStart, activeLastHeartbeat, sessions.isEmpty(), onLeave, now)
        );
    }

    private long sessionMinutes(AttendanceSession session, Instant from, Instant to, Instant now) {
        Instant start = session.getStartTime().isBefore(from) ? from : session.getStartTime();
        Instant rawEnd = session.getEndTime() != null ? session.getEndTime() : now;
        Instant end = rawEnd.isAfter(to) ? to : rawEnd;
        if (end.isBefore(start)) {
            return 0;
        }
        long elapsedMinutes = Duration.between(start, end).toMinutes();
        long breakMinutes = totalBreakMinutes(session, end);
        return Math.max(elapsedMinutes - breakMinutes, 0);
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
            return AttendanceDayStatus.COMPLETED;
        }
        if (totalMinutes >= settingsService.attendanceGraceMinutes()) {
            return AttendanceDayStatus.COMPLETED_WITH_GRACE;
        }
        return AttendanceDayStatus.UNDER_HOURS;
    }

    private boolean isStaleSession(LocalDate workDate, Instant activeStart, Instant now) {
        LocalDate today = LocalDate.now(attendanceZone);
        return workDate.isBefore(today) || Duration.between(activeStart, now).toMinutes() >= settingsService.attendanceMissingCheckoutMinutes();
    }

    private boolean isHeartbeatStale(Instant lastHeartbeatAt, Instant now) {
        return lastHeartbeatAt != null && Duration.between(lastHeartbeatAt, now).toMinutes() >= settingsService.attendanceHeartbeatStaleMinutes();
    }

    private boolean isOnApprovedLeave(Long userId, LocalDate date) {
        return leaveRequestRepository
                .findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(LeaveStatus.APPROVED, date, date)
                .stream()
                .anyMatch(leave -> leave.getUser().getId().equals(userId));
    }

    private AttendanceSessionResponse startSessionForUser(User user) {
        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(user.getId());
        if (!activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "This user already has an active session.");
        }

        AttendanceSession session = new AttendanceSession();
        session.setUser(user);
        Instant now = Instant.now();
        session.setStartTime(now);
        session.setLastHeartbeatAt(now);

        return map(repository.save(session));
    }

    private AttendanceSessionResponse closeSession(AttendanceSession session, Instant endTime) {
        session.setEndTime(endTime);
        if (session.getBreakStartedAt() != null) {
            session.setBreakMinutes(Math.toIntExact(totalBreakMinutes(session, endTime)));
            session.setBreakStartedAt(null);
        }
        long workedMinutes = sessionMinutes(session, session.getStartTime(), endTime, endTime);
        double hours = workedMinutes / 60.0;
        session.setTotalHours(BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP));
        return map(repository.save(session));
    }

    private AttendanceSession getCurrentUserActiveSession() {
        User currentUser = getCurrentUser();
        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found.");
        }
        return activeSessions.getFirst();
    }

    private long totalBreakMinutes(AttendanceSession session, Instant until) {
        long savedBreakMinutes = session.getBreakMinutes() != null ? session.getBreakMinutes() : 0;
        if (session.getBreakStartedAt() == null) {
            return savedBreakMinutes;
        }
        if (until.isBefore(session.getBreakStartedAt())) {
            return savedBreakMinutes;
        }
        return savedBreakMinutes + Duration.between(session.getBreakStartedAt(), until).toMinutes();
    }

    private User getCurrentUser() {
        String email = securityUtils.getCurrentUserEmail();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Current user not found"));
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
