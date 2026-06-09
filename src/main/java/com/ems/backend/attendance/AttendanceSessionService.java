package com.ems.backend.attendance;

import com.ems.backend.attendance.dto.AttendanceSessionResponse;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AttendanceSessionService {
    private final AttendanceSessionRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    public AttendanceSessionService(AttendanceSessionRepository repository, UserRepository userRepository, SecurityUtils securityUtils) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
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
