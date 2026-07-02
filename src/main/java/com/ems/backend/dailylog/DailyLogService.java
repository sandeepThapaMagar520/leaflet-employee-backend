package com.ems.backend.dailylog;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.dailylog.dto.CreateDailyLogRequest;
import com.ems.backend.dailylog.dto.DailyLogResponse;
import com.ems.backend.dailylog.dto.UpdateDailyLogRequest;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class DailyLogService {
    private final DailyLogRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    public DailyLogService(DailyLogRepository repository, UserRepository userRepository, SecurityUtils securityUtils) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
    }

    public DailyLogResponse createLog(CreateDailyLogRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(FORBIDDEN, "Admins can audit daily logs but cannot submit them.");
        }
        if (repository.existsByUserIdAndLogDate(currentUser.getId(), request.getLogDate())) {
            throw new ResponseStatusException(CONFLICT, "You already submitted a daily log for this date.");
        }

        DailyLog log = new DailyLog();
        log.setUser(currentUser);
        log.setLogDate(request.getLogDate());
        log.setSummary(request.getSummary());
        log.setProblemsFaced(request.getProblemsFaced());

        return map(repository.save(log));
    }

    public List<DailyLogResponse> getMyLogs() {
        String email = securityUtils.getCurrentUserEmail();
        return repository.findByUserEmailIgnoreCaseOrderByLogDateDesc(email).stream().map(this::map).toList();
    }

    public List<DailyLogResponse> getAllLogs() {
        return repository.findAllByOrderByLogDateDesc().stream()
                .filter(log -> log.getUser().getRole() != Role.ADMIN)
                .map(this::map)
                .toList();
    }

    public List<DailyLogResponse> getLogsByUser(Long userId) {
        return repository.findByUserIdOrderByLogDateDesc(userId).stream().map(this::map).toList();
    }

    public DailyLogResponse updateLog(Long logId, UpdateDailyLogRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(FORBIDDEN, "Admins can audit daily logs but cannot edit submissions.");
        }
        DailyLog log = repository.findById(logId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Daily log not found"));

        boolean ownsLog = log.getUser().getId().equals(currentUser.getId());
        boolean canManage = currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.MANAGER;
        if (!ownsLog && !canManage) {
            throw new ResponseStatusException(FORBIDDEN, "You can update only your own daily logs.");
        }

        if (repository.existsByUserIdAndLogDateAndIdNot(log.getUser().getId(), request.getLogDate(), logId)) {
            throw new ResponseStatusException(CONFLICT, "A daily log already exists for this date.");
        }

        log.setLogDate(request.getLogDate());
        log.setSummary(request.getSummary());
        log.setProblemsFaced(request.getProblemsFaced());
        return map(repository.save(log));
    }

    public List<DailyLogResponse> getLogsForExport() {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.MANAGER) {
            return getAllLogs();
        }
        return getMyLogs();
    }

    private User getCurrentUser() {
        return securityUtils.getCurrentUser();
    }

    private DailyLogResponse map(DailyLog log) {
        return new DailyLogResponse(
                log.getId(),
                log.getUser().getId(),
                log.getUser().getFullName(),
                log.getLogDate(),
                log.getSummary(),
                log.getProblemsFaced(),
                log.getCreatedAt()
        );
    }
}
