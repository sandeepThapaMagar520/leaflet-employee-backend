package com.ems.backend.outbox;

import com.ems.backend.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {
    private final OutboxAdminService service;

    public OutboxAdminController(OutboxAdminService service) { this.service = service; }

    @GetMapping("/failed")
    public PageResponse<OutboxRepository.OutboxAdminMessage> failed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.failed(page, size);
    }

    @GetMapping("/{id}/attempts")
    public List<OutboxRepository.OutboxAttempt> attempts(@PathVariable UUID id) { return service.attempts(id); }

    @GetMapping("/stats")
    public Map<String, Object> stats() { return service.stats(); }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable UUID id) { service.retry(id); }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) { service.cancel(id); }
}
