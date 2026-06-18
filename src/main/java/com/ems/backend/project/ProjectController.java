package com.ems.backend.project;

import com.ems.backend.common.PageResponse;
import com.ems.backend.project.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project, finance, note, and milestone management")
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Create project", description = "Admin and manager endpoint for creating a project and assigning its team.")
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List projects", description = "Returns visible projects. Optional page and size parameters return a paged response.")
    public Object getAllProjects(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        if (page != null || size != null) {
            return projectService.getAllProjectsPaged(page != null ? page : 0, size != null ? size : 20);
        }
        return projectService.getAllProjects();
    }

    @GetMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get project", description = "Returns a single project if the current user has access.")
    public ProjectResponse getProject(@PathVariable Long projectId) {
        return projectService.getProjectById(projectId);
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Update project", description = "Admin and project manager endpoint for editing project details, status, team, budget, and documents.")
    public ProjectResponse updateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return projectService.updateProject(projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Delete project", description = "Deletes a project and related project data according to database constraints.")
    public void deleteProject(@PathVariable Long projectId) {
        projectService.deleteProject(projectId);
    }

    @GetMapping("/{projectId}/task-boards")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List project task boards", description = "Returns default and custom Kanban boards for a project.")
    public List<ProjectTaskBoardResponse> listTaskBoards(@PathVariable Long projectId) {
        return projectService.listTaskBoards(projectId);
    }

    @PostMapping("/{projectId}/task-boards")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Create project task board", description = "Admins and project managers can add custom Kanban boards for project tasks.")
    public ProjectTaskBoardResponse createTaskBoard(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateProjectTaskBoardRequest request
    ) {
        return projectService.createTaskBoard(projectId, request);
    }

    @PutMapping("/{projectId}/task-boards/order")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Reorder project task boards", description = "Admins and project managers can move Kanban boards left or right.")
    public List<ProjectTaskBoardResponse> reorderTaskBoards(
            @PathVariable Long projectId,
            @Valid @RequestBody ReorderProjectTaskBoardsRequest request
    ) {
        return projectService.reorderTaskBoards(projectId, request);
    }

    @GetMapping("/{projectId}/payments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "List project payments")
    public List<PaymentResponse> listPayments(@PathVariable Long projectId) {
        return projectService.listPayments(projectId);
    }

    @PostMapping("/{projectId}/payments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Record project payment")
    public PaymentResponse addPayment(
            @PathVariable Long projectId,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        return projectService.addPayment(projectId, request);
    }

    @PutMapping("/{projectId}/payments/{paymentId}/attachments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Update payment bills", description = "Replaces the optional bill attachments saved for a project payment.")
    public PaymentResponse updatePaymentAttachments(
            @PathVariable Long projectId,
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentAttachmentsRequest request
    ) {
        return projectService.updatePaymentAttachments(projectId, paymentId, request);
    }

    @GetMapping("/{projectId}/notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List project notes", description = "Returns project notes, optionally filtered by note type.")
    public List<ProjectNoteResponse> listNotes(
            @PathVariable Long projectId,
            @RequestParam(required = false) ProjectNoteType type
    ) {
        return projectService.listNotes(projectId, type);
    }

    @PostMapping("/{projectId}/notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Add project note")
    public ProjectNoteResponse addNote(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateProjectNoteRequest request
    ) {
        return projectService.addNote(projectId, request);
    }

    @PutMapping("/notes/{noteId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Update project note")
    public ProjectNoteResponse updateNote(
            @PathVariable Long noteId,
            @Valid @RequestBody UpdateProjectNoteRequest request
    ) {
        return projectService.updateNote(noteId, request);
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Delete project note")
    public void deleteNote(@PathVariable Long noteId) {
        projectService.deleteNote(noteId);
    }

    @GetMapping("/{projectId}/milestones")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List project milestones")
    public List<ProjectMilestoneResponse> listMilestones(@PathVariable Long projectId) {
        return projectService.listMilestones(projectId);
    }

    @PostMapping("/{projectId}/milestones")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Add project milestone")
    public ProjectMilestoneResponse addMilestone(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateProjectMilestoneRequest request
    ) {
        return projectService.addMilestone(projectId, request);
    }

    @PatchMapping("/milestones/{milestoneId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Complete milestone")
    public ProjectMilestoneResponse completeMilestone(@PathVariable Long milestoneId) {
        return projectService.setMilestoneCompleted(milestoneId, true);
    }

    @PatchMapping("/milestones/{milestoneId}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Reopen milestone")
    public ProjectMilestoneResponse reopenMilestone(@PathVariable Long milestoneId) {
        return projectService.setMilestoneCompleted(milestoneId, false);
    }

    @DeleteMapping("/milestones/{milestoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Delete milestone")
    public void deleteMilestone(@PathVariable Long milestoneId) {
        projectService.deleteMilestone(milestoneId);
    }
}
