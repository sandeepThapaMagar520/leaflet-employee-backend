package com.ems.backend.dailylog;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
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
import java.time.LocalDate;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class DailyLogService {
    private final DailyLogRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final AuthorizationPolicyService authorizationPolicy;
    private final SecurityAuditService auditService;

    public DailyLogService(
            DailyLogRepository repository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            AuthorizationPolicyService authorizationPolicy,
            SecurityAuditService auditService
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.authorizationPolicy = authorizationPolicy;
        this.auditService = auditService;
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

    @Transactional(readOnly = true)
    public PageResponse<DailyLogResponse> getMyLogs(int page, int size) {
        Long userId = getCurrentUser().getId();
        var pageable = Pagination.page(page, size, "logDate", "desc", java.util.Set.of("logDate"));
        return PageResponse.from(repository.findByUserId(userId, pageable), this::map);
    }

    @Transactional(readOnly = true)
    public PageResponse<DailyLogResponse> getAllLogs(int page, int size) {
        User currentUser = getCurrentUser();
        var pageable = Pagination.page(page, size, "logDate", "desc", java.util.Set.of("logDate"));
        var logs = switch (currentUser.getRole()) {
            case ADMIN -> repository.findAllNonAdmin(pageable);
            case MANAGER -> repository.findVisibleToManager(currentUser.getId(), pageable);
            case EMPLOYEE -> repository.findByUserId(currentUser.getId(), pageable);
        };
        return PageResponse.from(logs, this::map);
    }

    public List<DailyLogResponse> getLogsByUser(Long userId) {
        User currentUser = getCurrentUser();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        authorizationPolicy.requireViewDailyLog(currentUser, target);
        return repository.findByUserIdOrderByLogDateDesc(userId).stream().map(this::map).toList();
    }

    public DailyLogResponse updateLog(Long logId, UpdateDailyLogRequest request) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(FORBIDDEN, "Admins can audit daily logs but cannot edit submissions.");
        }
        DailyLog log = repository.findById(logId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Daily log not found"));

        authorizationPolicy.requireEditDailyLog(currentUser, log.getUser());

        if (repository.existsByUserIdAndLogDateAndIdNot(log.getUser().getId(), request.getLogDate(), logId)) {
            throw new ResponseStatusException(CONFLICT, "A daily log already exists for this date.");
        }

        boolean editingAnotherUser = !log.getUser().getId().equals(currentUser.getId());
        log.setLogDate(request.getLogDate());
        log.setSummary(request.getSummary());
        log.setProblemsFaced(request.getProblemsFaced());
        DailyLog saved = repository.save(log);
        if (editingAnotherUser) {
            auditService.recordWithDetails(
                    currentUser.getId(),
                    log.getUser().getId(),
                    "DAILY_LOG_CHANGED_BY_MANAGER",
                    "SUCCESS",
                    "SCOPED_MANAGER_EDIT",
                    "logId=" + log.getId(),
                    log.getUser().getEmail(),
                    RequestMetadata.current()
            );
        }
        return map(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DailyLogResponse> getLogsForExport(
            LocalDate from, LocalDate to, int page, int size
    ) {
        User currentUser = getCurrentUser();
        var pageable = Pagination.page(page, size, "logDate", "desc", java.util.Set.of("logDate"));
        var logs = switch (currentUser.getRole()) {
            case ADMIN -> repository.findAllNonAdminBetween(from, to, pageable);
            case MANAGER -> repository.findVisibleToManagerBetween(currentUser.getId(), from, to, pageable);
            case EMPLOYEE -> repository.findByUserIdBetween(currentUser.getId(), from, to, pageable);
        };
        return PageResponse.from(logs, this::map);
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
