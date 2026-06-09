package com.ems.backend.common;

import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectRepository;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ProjectAccessService {
    private final ProjectRepository projectRepository;

    public ProjectAccessService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public boolean canAccessProject(User user, Project project) {
        if (user.getRole() == Role.ADMIN) {
            return true;
        }
        if (user.getRole() == Role.MANAGER && project.getManager().getId().equals(user.getId())) {
            return true;
        }
        if (user.getRole() == Role.EMPLOYEE) {
            return project.getAssignedEmployees().stream()
                    .anyMatch(employee -> employee.getId().equals(user.getId()));
        }
        return false;
    }

    public Project requireAccessibleProject(Long projectId, User user) {
        Project project = projectRepository.findByIdWithDetails(projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Project not found"));
        if (!canAccessProject(user, project)) {
            throw new ResponseStatusException(FORBIDDEN, "You do not have access to this project");
        }
        return project;
    }

    public boolean canManageProject(User user, Project project) {
        if (user.getRole() == Role.ADMIN) {
            return true;
        }
        return user.getRole() == Role.MANAGER && project.getManager().getId().equals(user.getId());
    }

    public Project requireManageableProject(Long projectId, User user) {
        Project project = projectRepository.findByIdWithDetails(projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Project not found"));
        if (!canManageProject(user, project)) {
            throw new ResponseStatusException(FORBIDDEN, "You can manage only projects assigned to you");
        }
        return project;
    }

    public boolean canManageNotes(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.MANAGER;
    }

    public boolean canViewFinancials(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.MANAGER;
    }
}
