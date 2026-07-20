package com.ems.backend.project;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectTransitionPolicyTest {
    private final ProjectTransitionPolicy policy = new ProjectTransitionPolicy();

    @Test
    void allowsDocumentedForwardAndPauseTransitions() {
        assertDoesNotThrow(() -> policy.requireTransition(ProjectStatus.PLANNED, ProjectStatus.ACTIVE));
        assertDoesNotThrow(() -> policy.requireTransition(ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD));
        assertDoesNotThrow(() -> policy.requireTransition(ProjectStatus.ON_HOLD, ProjectStatus.COMPLETED));
    }

    @Test
    void completedProjectIsTerminal() {
        assertThrows(
                ResponseStatusException.class,
                () -> policy.requireTransition(ProjectStatus.COMPLETED, ProjectStatus.ACTIVE)
        );
    }
}
