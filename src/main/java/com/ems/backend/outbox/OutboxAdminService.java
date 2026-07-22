package com.ems.backend.outbox;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxAdminService {
    private final OutboxRepository repository;
    private final SecurityUtils securityUtils;
    private final SecurityAuditService audit;

    public OutboxAdminService(OutboxRepository repository, SecurityUtils securityUtils, SecurityAuditService audit) {
        this.repository = repository;
        this.securityUtils = securityUtils;
        this.audit = audit;
    }

    public PageResponse<OutboxRepository.OutboxAdminMessage> failed(int page, int size) {
        Pagination.page(page, size, "createdAt", "desc", java.util.Set.of("createdAt"));
        long total = repository.failedCount();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        List<OutboxRepository.OutboxAdminMessage> content = repository.listFailed(page, size);
        return new PageResponse<>(content, page, size, content.size(), total, pages,
                page == 0, page + 1 >= pages, List.of("createdAt,desc", "id,desc"));
    }

    public List<OutboxRepository.OutboxAttempt> attempts(UUID id) { return repository.attempts(id); }

    public Map<String, Object> stats() { return repository.queueStats(); }

    @Transactional
    public void retry(UUID id) {
        if (repository.retryFailed(id) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Message is not safe or eligible for retry.");
        }
        record("OUTBOX_MANUAL_RETRY", id);
    }

    @Transactional
    public void cancel(UUID id) {
        if (repository.cancel(id) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Message is not pending or retryable.");
        }
        record("OUTBOX_MANUAL_CANCEL", id);
    }

    private void record(String type, UUID id) {
        var actor = securityUtils.getCurrentUser();
        audit.recordWithDetails(actor.getId(), null, type, "SUCCESS", null,
                "outboxMessageId=" + id, actor.getEmail(), RequestMetadata.current());
    }
}
