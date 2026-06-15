package com.ems.backend.project;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.project.dto.*;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.task.Task;
import com.ems.backend.task.TaskRepository;
import com.ems.backend.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class ProjectService {
    private static final Set<String> PAYMENT_ATTACHMENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final ProjectPaymentRepository projectPaymentRepository;
    private final ProjectNoteRepository projectNoteRepository;
    private final ProjectMilestoneRepository projectMilestoneRepository;
    private final SecurityUtils securityUtils;
    private final ProjectAccessService projectAccessService;
    private final NotificationService notificationService;

    public ProjectService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            TaskRepository taskRepository,
            ProjectPaymentRepository projectPaymentRepository,
            ProjectNoteRepository projectNoteRepository,
            ProjectMilestoneRepository projectMilestoneRepository,
            SecurityUtils securityUtils,
            ProjectAccessService projectAccessService,
            NotificationService notificationService
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.projectPaymentRepository = projectPaymentRepository;
        this.projectNoteRepository = projectNoteRepository;
        this.projectMilestoneRepository = projectMilestoneRepository;
        this.securityUtils = securityUtils;
        this.projectAccessService = projectAccessService;
        this.notificationService = notificationService;
    }

    public ProjectResponse createProject(CreateProjectRequest request) {
        User manager = getUserById(request.managerId());
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Manager must have MANAGER or ADMIN role");
        }

        User currentUser = getCurrentUser();
        if (currentUser.getRole() == Role.MANAGER && !manager.getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Managers can create projects only for themselves");
        }

        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setStatus(ProjectStatus.PLANNED);
        project.setStartDate(request.startDate());
        project.setDueDate(request.dueDate());
        project.setManager(manager);
        project.setCreatedBy(currentUser);
        project.setClientNotes(request.clientNotes());
        project.setDocumentUrl(request.documentUrl());
        project.setBudgetAmount(request.budgetAmount() != null ? request.budgetAmount() : BigDecimal.ZERO);
        project.setInternalNotes(request.internalNotes());

        Project saved = projectRepository.save(project);

        if (request.assignedEmployeeIds() != null) {
            Set<User> employees = request.assignedEmployeeIds().stream()
                    .map(this::getUserById)
                    .collect(Collectors.toSet());
            saved.setAssignedEmployees(employees);
            notifyNewlyAssignedEmployees(saved, Set.of(), employees);
            saved = projectRepository.save(saved);
            projectRepository.flush();
            applyMemberPermissions(saved.getId(), employees, request.memberPermissions());
        }

        return map(saved);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getAllProjects() {
        return getAllProjectsInternal(getCurrentUser());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> getAllProjectsPaged(int page, int size) {
        User currentUser = getCurrentUser();
        List<ProjectResponse> all = getAllProjectsInternal(currentUser);
        return PageResponse.of(all, page, size);
    }

    private List<ProjectResponse> getAllProjectsInternal(User currentUser) {
        List<Project> projects = switch (currentUser.getRole()) {
            case ADMIN -> projectRepository.findAllWithDetails();
            case MANAGER, EMPLOYEE -> projectRepository.findAllAccessibleToUser(currentUser.getId());
        };
        return projects.stream().map(project -> mapForUser(project, currentUser)).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProjectById(Long projectId) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireAccessibleProject(projectId, currentUser);
        return mapForUser(project, currentUser);
    }

    public ProjectResponse updateProject(Long projectId, UpdateProjectRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);

        User manager = getUserById(request.managerId());
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Manager must have MANAGER or ADMIN role");
        }
        if (currentUser.getRole() == Role.MANAGER && !manager.getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Managers cannot transfer projects to another manager");
        }

        project.setName(request.name());
        project.setDescription(request.description());
        project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setDueDate(request.dueDate());
        project.setManager(manager);
        project.setClientNotes(request.clientNotes());
        project.setDocumentUrl(request.documentUrl());
        if (request.budgetAmount() != null) {
            project.setBudgetAmount(request.budgetAmount());
        }
        project.setInternalNotes(request.internalNotes());

        if (request.assignedEmployeeIds() != null) {
            Set<Long> previousIds = project.getAssignedEmployees().stream()
                    .map(User::getId)
                    .collect(Collectors.toSet());
            Set<User> employees = request.assignedEmployeeIds().stream()
                    .map(this::getUserById)
                    .collect(Collectors.toSet());
            project.setAssignedEmployees(employees);
            notifyNewlyAssignedEmployees(project, previousIds, employees);
            projectRepository.save(project);
            projectRepository.flush();
            applyMemberPermissions(project.getId(), employees, request.memberPermissions());
        }

        return map(projectRepository.save(project));
    }

    public void deleteProject(Long projectId) {
        User currentUser = getCurrentUser();
        projectAccessService.requireManageableProject(projectId, currentUser);
        projectRepository.deleteById(projectId);
    }

    public List<PaymentResponse> listPayments(Long projectId) {
        User currentUser = getCurrentUser();
        if (!projectAccessService.canViewFinancials(currentUser)) {
            throw new ResponseStatusException(FORBIDDEN, "You do not have permission to view project finances");
        }
        projectAccessService.requireAccessibleProject(projectId, currentUser);
        return projectPaymentRepository.findAllByProjectIdWithCreatorOrderByPaidAtDesc(projectId).stream()
                .map(this::mapPayment)
                .toList();
    }

    public PaymentResponse addPayment(Long projectId, CreatePaymentRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireAccessibleProject(projectId, currentUser);
        if (!projectAccessService.canViewFinancials(currentUser)) {
            throw new ResponseStatusException(FORBIDDEN, "You do not have permission to manage project finances");
        }
        ProjectPayment payment = new ProjectPayment();
        payment.setProject(project);
        payment.setAmount(request.amount());
        payment.setPaidAt(request.paidAt());
        payment.setReferenceNote(request.referenceNote());
        payment.setCreatedBy(currentUser);
        replacePaymentAttachments(payment, request.attachments());
        return mapPayment(projectPaymentRepository.save(payment));
    }

    public PaymentResponse updatePaymentAttachments(
            Long projectId,
            Long paymentId,
            UpdatePaymentAttachmentsRequest request
    ) {
        User currentUser = getCurrentUser();
        projectAccessService.requireManageableProject(projectId, currentUser);
        ProjectPayment payment = projectPaymentRepository.findByIdAndProjectIdWithCreator(paymentId, projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Payment not found"));
        replacePaymentAttachments(payment, request.attachments());
        return mapPayment(projectPaymentRepository.save(payment));
    }

    public List<ProjectNoteResponse> listNotes(Long projectId, ProjectNoteType type) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireAccessibleProject(projectId, currentUser);

        List<ProjectNote> notes;
        if (type != null) {
            if (type == ProjectNoteType.ADMIN_ONLY && !projectAccessService.canViewAdminOnlyNotes(currentUser, project)) {
                throw new ResponseStatusException(FORBIDDEN, "You do not have permission to view admin-only notes");
            }
            notes = projectNoteRepository.findAllByProjectIdAndNoteTypeWithCreatorOrderByCreatedAtDesc(projectId, type);
        } else {
            notes = projectNoteRepository.findAllByProjectIdWithCreatorOrderByCreatedAtDesc(projectId);
        }

        return notes.stream()
                .filter(note -> projectAccessService.canViewAdminOnlyNotes(currentUser, project)
                        || note.getNoteType() == ProjectNoteType.TEAM)
                .map(this::mapNote)
                .toList();
    }

    public ProjectNoteResponse addNote(Long projectId, com.ems.backend.project.dto.CreateProjectNoteRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireAccessibleProject(projectId, currentUser);
        if (!projectAccessService.canAddNotes(currentUser, project)) {
            throw new ResponseStatusException(FORBIDDEN, "You do not have permission to add notes to this project");
        }
        if (request.noteType() == ProjectNoteType.ADMIN_ONLY
                && !projectAccessService.canManageNotes(currentUser, project)) {
            throw new ResponseStatusException(FORBIDDEN, "Employees can add only project-team notes");
        }

        ProjectNote note = new ProjectNote();
        note.setProject(project);
        note.setContent(request.content());
        note.setNoteType(request.noteType());
        note.setCreatedBy(currentUser);
        return mapNote(projectNoteRepository.save(note));
    }

    public ProjectNoteResponse updateNote(Long noteId, UpdateProjectNoteRequest request) {
        User currentUser = getCurrentUser();
        ProjectNote note = projectNoteRepository.findByIdWithDetails(noteId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Note not found"));
        Project project = projectAccessService.requireAccessibleProject(note.getProject().getId(), currentUser);
        if (!projectAccessService.canManageNotes(currentUser, project)) {
            throw new ResponseStatusException(FORBIDDEN, "Only admins and the project manager can edit notes");
        }

        note.setContent(request.content());
        note.setNoteType(request.noteType());
        return mapNote(projectNoteRepository.save(note));
    }

    public void deleteNote(Long noteId) {
        User currentUser = getCurrentUser();
        ProjectNote note = projectNoteRepository.findByIdWithDetails(noteId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Note not found"));
        Project project = projectAccessService.requireAccessibleProject(note.getProject().getId(), currentUser);
        if (!projectAccessService.canManageNotes(currentUser, project)) {
            throw new ResponseStatusException(FORBIDDEN, "Only admins and the project manager can delete notes");
        }
        projectNoteRepository.delete(note);
    }

    public List<ProjectMilestoneResponse> listMilestones(Long projectId) {
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(projectId, currentUser);
        return projectMilestoneRepository.findByProjectIdOrderByDueDateAscCreatedAtAsc(projectId).stream()
                .map(this::mapMilestone)
                .toList();
    }

    public ProjectMilestoneResponse addMilestone(Long projectId, CreateProjectMilestoneRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);
        ProjectMilestone milestone = new ProjectMilestone();
        milestone.setProject(project);
        milestone.setTitle(request.title());
        milestone.setDescription(request.description());
        milestone.setDueDate(request.dueDate());
        return mapMilestone(projectMilestoneRepository.save(milestone));
    }

    public ProjectMilestoneResponse setMilestoneCompleted(Long milestoneId, boolean completed) {
        User currentUser = getCurrentUser();
        ProjectMilestone milestone = projectMilestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Milestone not found"));
        projectAccessService.requireManageableProject(milestone.getProject().getId(), currentUser);
        milestone.setCompleted(completed);
        return mapMilestone(projectMilestoneRepository.save(milestone));
    }

    public void deleteMilestone(Long milestoneId) {
        User currentUser = getCurrentUser();
        ProjectMilestone milestone = projectMilestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Milestone not found"));
        projectAccessService.requireManageableProject(milestone.getProject().getId(), currentUser);
        projectMilestoneRepository.delete(milestone);
    }

    private ProjectMilestoneResponse mapMilestone(ProjectMilestone milestone) {
        return new ProjectMilestoneResponse(
                milestone.getId(),
                milestone.getProject().getId(),
                milestone.getTitle(),
                milestone.getDescription(),
                milestone.getDueDate(),
                milestone.isCompleted(),
                milestone.getCreatedAt(),
                milestone.getUpdatedAt()
        );
    }

    private ProjectNoteResponse mapNote(ProjectNote note) {
        return new ProjectNoteResponse(
                note.getId(),
                note.getContent(),
                note.getNoteType(),
                note.getCreatedBy().getFullName(),
                note.getCreatedAt()
        );
    }

    private PaymentResponse mapPayment(ProjectPayment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAmount(),
                payment.getPaidAt(),
                payment.getReferenceNote(),
                payment.getCreatedBy().getFullName(),
                payment.getAttachments().stream()
                        .map(attachment -> new PaymentAttachmentResponse(
                                attachment.getId(),
                                attachment.getFileUrl(),
                                attachment.getFileName(),
                                attachment.getFileType()
                        ))
                        .toList()
        );
    }

    private void replacePaymentAttachments(
            ProjectPayment payment,
            List<PaymentAttachmentRequest> requestedAttachments
    ) {
        List<PaymentAttachmentRequest> attachments = requestedAttachments != null
                ? requestedAttachments
                : List.of();
        for (PaymentAttachmentRequest attachment : attachments) {
            if (!PAYMENT_ATTACHMENT_TYPES.contains(attachment.fileType())) {
                throw new ResponseStatusException(BAD_REQUEST, "Payment bills must be PDF, JPG, or PNG files");
            }
        }

        payment.getAttachments().clear();
        for (PaymentAttachmentRequest request : attachments) {
            ProjectPaymentAttachment attachment = new ProjectPaymentAttachment();
            attachment.setPayment(payment);
            attachment.setFileUrl(request.fileUrl().trim());
            attachment.setFileName(request.fileName().trim());
            attachment.setFileType(request.fileType());
            payment.getAttachments().add(attachment);
        }
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }

    private User getCurrentUser() {
        return securityUtils.getCurrentUser();
    }

    private void notifyNewlyAssignedEmployees(Project project, Set<Long> previousIds, Set<User> employees) {
        for (User employee : employees) {
            if (!previousIds.contains(employee.getId())) {
                notificationService.notifyUser(
                        employee,
                        NotificationType.PROJECT_ASSIGNED,
                        "Added to project team",
                        "You were added to \"" + project.getName() + "\"",
                        "/projects/" + project.getId()
                );
            }
        }
    }

    private ProjectResponse mapForUser(Project project, User viewer) {
        boolean canViewFinancials = projectAccessService.canViewFinancials(viewer);
        return map(project, canViewFinancials);
    }

    private ProjectResponse map(Project project) {
        return map(project, true);
    }

    private ProjectResponse map(Project project, boolean includeFinancials) {
        List<Task> tasks = taskRepository.findByProjectId(project.getId());
        int progress = 0;
        if (!tasks.isEmpty()) {
            long doneCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
            progress = (int) Math.round((double) doneCount / tasks.size() * 100);
        }

        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal lastAmount = null;
        java.time.Instant lastAt = null;
        String lastNote = null;
        if (includeFinancials) {
            totalPaid = projectPaymentRepository.sumAmountByProjectId(project.getId());
            var lastOpt = projectPaymentRepository.findLatestByProjectId(project.getId());
            lastAmount = lastOpt.map(ProjectPayment::getAmount).orElse(null);
            lastAt = lastOpt.map(ProjectPayment::getPaidAt).orElse(null);
            lastNote = lastOpt.map(ProjectPayment::getReferenceNote).orElse(null);
        }

        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getStatus(),
                project.getStartDate(),
                project.getDueDate(),
                project.getManager().getId(),
                project.getManager().getFullName(),
                project.getCreatedBy().getId(),
                project.getAssignedEmployees().stream()
                        .map(u -> new ProjectResponse.ProjectEmployeeResponse(
                                u.getId(),
                                u.getFullName(),
                                Boolean.TRUE.equals(projectRepository.canMemberManageTasks(project.getId(), u.getId())),
                                Boolean.TRUE.equals(projectRepository.canMemberAddNotes(project.getId(), u.getId()))
                        ))
                        .toList(),
                project.getClientNotes(),
                includeFinancials ? project.getInternalNotes() : null,
                project.getDocumentUrl(),
                includeFinancials ? project.getBudgetAmount() : BigDecimal.ZERO,
                totalPaid,
                lastAmount,
                lastAt,
                lastNote,
                progress,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private void applyMemberPermissions(
            Long projectId,
            Set<User> assignedEmployees,
            List<ProjectMemberPermissionRequest> requestedPermissions
    ) {
        var permissionsByUserId = requestedPermissions == null
                ? java.util.Map.<Long, ProjectMemberPermissionRequest>of()
                : requestedPermissions.stream().collect(Collectors.toMap(
                        ProjectMemberPermissionRequest::userId,
                        permission -> permission,
                        (first, second) -> second
                ));

        for (User employee : assignedEmployees) {
            ProjectMemberPermissionRequest permission = permissionsByUserId.get(employee.getId());
            projectRepository.updateMemberPermissions(
                    projectId,
                    employee.getId(),
                    permission != null && permission.canManageTasks(),
                    permission != null && permission.canAddNotes()
            );
        }
    }
}
