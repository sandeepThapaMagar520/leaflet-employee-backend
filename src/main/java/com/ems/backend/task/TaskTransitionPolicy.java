package com.ems.backend.task;

import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectTaskBoard;
import com.ems.backend.project.ProjectTaskBoardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TaskTransitionPolicy {
    private static final Map<String, Set<String>> CORE = Map.of(
            "TODO", Set.of("IN_PROGRESS", "BLOCKED"),
            "IN_PROGRESS", Set.of("BLOCKED", "DONE"),
            "BLOCKED", Set.of("IN_PROGRESS"),
            "DONE", Set.of("IN_PROGRESS")
    );

    private final ProjectTaskBoardRepository boardRepository;

    public TaskTransitionPolicy(ProjectTaskBoardRepository boardRepository) {
        this.boardRepository = boardRepository;
    }

    public void requireTransition(Project project, String current, String target, boolean canReopen) {
        if (current.equals(target)) return;
        if ("DONE".equals(current) && !canReopen) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a project task manager can reopen a completed task");
        }
        if (!"DONE".equals(current) && "DONE".equals(target)) {
            if (CORE.containsKey(current)) {
                return;
            }
            List<ProjectTaskBoard> boards = boards(project);
            int from = indexOf(boards, current);
            if (from >= 0 && !boards.get(from).isTerminal()) {
                return;
            }
        }
        if (CORE.containsKey(current) && CORE.containsKey(target)) {
            if (!CORE.get(current).contains(target)) {
                reject(current, target);
            }
            return;
        }

        List<ProjectTaskBoard> boards = boards(project);
        int from = indexOf(boards, current);
        int to = indexOf(boards, target);
        if (from < 0 || to < 0 || Math.abs(from - to) != 1) {
            reject(current, target);
        }
        if (boards.get(from).isTerminal() && !canReopen) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a project task manager can reopen a terminal task");
        }
    }

    private List<ProjectTaskBoard> boards(Project project) {
        return boardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(project.getId());
    }

    private int indexOf(List<ProjectTaskBoard> boards, String status) {
        for (int i = 0; i < boards.size(); i++) {
            if (boards.get(i).getStatusKey().equals(status)) return i;
        }
        return -1;
    }

    private void reject(String current, String target) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Task cannot transition from " + current + " to " + target
        );
    }
}
