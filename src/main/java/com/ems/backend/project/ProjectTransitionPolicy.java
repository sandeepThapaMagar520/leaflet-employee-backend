package com.ems.backend.project;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

@Component
public class ProjectTransitionPolicy {
    private static final Map<ProjectStatus, Set<ProjectStatus>> ALLOWED = Map.of(
            ProjectStatus.PLANNED, Set.of(ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD),
            ProjectStatus.ACTIVE, Set.of(ProjectStatus.ON_HOLD, ProjectStatus.COMPLETED),
            ProjectStatus.ON_HOLD, Set.of(ProjectStatus.ACTIVE, ProjectStatus.COMPLETED),
            ProjectStatus.COMPLETED, Set.of()
    );

    public void requireTransition(ProjectStatus current, ProjectStatus target) {
        if (current == target) return;
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Project cannot transition from " + current + " to " + target
            );
        }
    }

    public void requireMutable(Project project) {
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed projects are read-only");
        }
    }
}
