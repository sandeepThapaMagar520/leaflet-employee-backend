package com.ems.backend.task;

import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectTaskBoard;
import com.ems.backend.project.ProjectTaskBoardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskTransitionPolicyTest {
    @Mock
    private ProjectTaskBoardRepository boardRepository;

    private TaskTransitionPolicy policy;
    private Project project;

    @BeforeEach
    void setUp() {
        policy = new TaskTransitionPolicy(boardRepository);
        project = new Project();
        project.setId(1L);
    }

    @Test
    void everyActiveCoreStatusCanMoveDirectlyToDone() {
        assertDoesNotThrow(() -> policy.requireTransition(project, "TODO", "DONE", false));
        assertDoesNotThrow(() -> policy.requireTransition(project, "IN_PROGRESS", "DONE", false));
        assertDoesNotThrow(() -> policy.requireTransition(project, "BLOCKED", "DONE", false));
    }

    @Test
    void activeCustomStatusCanMoveDirectlyToDoneWithoutBeingAdjacent() {
        when(boardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(project.getId()))
                .thenReturn(java.util.List.of(
                        board("CUSTOM_REVIEW", false),
                        board("CUSTOM_APPROVAL", false),
                        board("DONE", true)
                ));

        assertDoesNotThrow(() -> policy.requireTransition(project, "CUSTOM_REVIEW", "DONE", false));
    }

    @Test
    void terminalCustomStatusIsNotTreatedAsActive() {
        when(boardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(project.getId()))
                .thenReturn(java.util.List.of(
                        board("CUSTOM_TERMINAL", true),
                        board("CUSTOM_REVIEW", false),
                        board("DONE", true)
                ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> policy.requireTransition(project, "CUSTOM_TERMINAL", "DONE", false)
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void completedTaskStillRequiresTaskManagementPermissionToReopen() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> policy.requireTransition(project, "DONE", "IN_PROGRESS", false)
        );

        assertEquals(403, exception.getStatusCode().value());
        assertDoesNotThrow(() -> policy.requireTransition(project, "DONE", "IN_PROGRESS", true));
    }

    private static ProjectTaskBoard board(String statusKey, boolean terminal) {
        ProjectTaskBoard board = new ProjectTaskBoard();
        board.setStatusKey(statusKey);
        board.setTerminal(terminal);
        return board;
    }
}
