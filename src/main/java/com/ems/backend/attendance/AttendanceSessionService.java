package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceDaySummaryResponse;
import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AttendanceSessionService {
    private static final long DEFAULT_REQUIRED_MINUTES = 7 * 60;
    private static final long DEFAULT_GRACE_MINUTES = 6 * 60;

    private final AttendanceSessionRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final ZoneId attendanceZone;
    private final long requiredMinutes;
    private final long graceMinutes;

    public AttendanceSessionService(
            AttendanceSessionRepository repository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            @Value("${app.attendance.zone-id:Asia/Kathmandu}") String attendanceZoneId,
            @Value("${app.attendance.required-minutes:" + DEFAULT_REQUIRED_MINUTES + "}") long requiredMinutes,
            @Value("${app.attendance.grace-minutes:" + DEFAULT_GRACE_MINUTES + "}") long graceMinutes
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.attendanceZone = ZoneId.of(attendanceZoneId);
        this.requiredMinutes = requiredMinutes;
        this.graceMinutes = graceMinutes;
    }

    public AttendanceSessionResponse startSession() {
        User currentUser = getCurrentUser();

        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (!activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "You already have an active session.");
        }

        AttendanceSession session = new AttendanceSession();
        session.setUser(currentUser);
        session.setStartTime(Instant.now());

        return map(repository.save(session));
    }

    public AttendanceSessionResponse endSession() {
        User currentUser = getCurrentUser();

        List<AttendanceSession> activeSessions = repository.findByUserIdAndEndTimeIsNull(currentUser.getId());
        if (activeSessions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No active session found to end.");
        }

        AttendanceSession session = activeSessions.getFirst();
        session.setEndTime(Instant.now());
        
        Duration duration = Duration.between(session.getStartTime(), session.getEndTime());
        double hours = duration.toMillis() / 3600000.0;
        session.setTotalHours(BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP));

        return map(repository.save(session));
    }

    public List<AttendanceSessionResponse> getMySessions() {
        String email = securityUtils.getCurrentUserEmail();
        return repository.findByUserEmailIgnoreCaseOrderByStartTimeDesc(email).stream().map(this::map).toList();
    }

    public List<AttendanceSessionResponse> getAllSessions() {
        return repository.findAllByOrderByStartTimeDesc().stream().map(this::map).toList();
    }

    public AttendanceDaySummaryResponse getMyTodaySummary() {
        User currentUser = getCurrentUser();
        return buildSummary(currentUser, LocalDate.now(attendanceZone), Instant.now());
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

        return userRepository.findAll().stream()
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> buildSummary(user, workDate, sessionsByUser.getOrDefault(user.getId(), List.of()), now))
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

    private AttendanceDaySummaryResponse buildSummary(User user, LocalDate workDate, Instant now) {
        Instant from = workDate.atStartOfDay(attendanceZone).toInstant();
        Instant to = workDate.plusDays(1).atStartOfDay(attendanceZone).toInstant();
        List<AttendanceSession> sessions = repository
                .findUserSessionsOverlappingDay(user.getId(), from, to);
        return buildSummary(user, workDate, sessions, now);
    }

    private AttendanceDaySummaryResponse buildSummary(User user, LocalDate workDate, List<AttendanceSession> sessions, Instant now) {
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

        long totalMinutes = sessions.stream()
                .mapToLong(session -> sessionMinutes(session, from, to, now))
                .sum();
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
                resolveStatus(totalMinutes, activeStart != null, sessions.isEmpty())
        );
    }

    private long sessionMinutes(AttendanceSession session, Instant from, Instant to, Instant now) {
        Instant start = session.getStartTime().isBefore(from) ? from : session.getStartTime();
        Instant rawEnd = session.getEndTime() != null ? session.getEndTime() : now;
        Instant end = rawEnd.isAfter(to) ? to : rawEnd;
        if (end.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, end).toMinutes();
    }

    private AttendanceDayStatus resolveStatus(long totalMinutes, boolean active, boolean noSessions) {
        if (active) {
            return AttendanceDayStatus.IN_PROGRESS;
        }
        if (noSessions) {
            return AttendanceDayStatus.NO_ACTIVITY;
        }
        if (totalMinutes >= requiredMinutes) {
            return AttendanceDayStatus.COMPLETED;
        }
        if (totalMinutes >= graceMinutes) {
            return AttendanceDayStatus.COMPLETED_WITH_GRACE;
        }
        return AttendanceDayStatus.UNDER_HOURS;
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
                session.getTotalHours()
        );
    }
}
