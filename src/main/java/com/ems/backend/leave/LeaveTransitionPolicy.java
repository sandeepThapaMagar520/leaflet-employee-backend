package com.ems.backend.leave;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LeaveTransitionPolicy {
    public void requireTransition(LeaveStatus current, LeaveStatus target) {
        boolean allowed = current == LeaveStatus.PENDING
                && (target == LeaveStatus.APPROVED
                || target == LeaveStatus.REJECTED
                || target == LeaveStatus.CANCELLED);
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Leave request cannot transition from " + current + " to " + target
            );
        }
    }
}
