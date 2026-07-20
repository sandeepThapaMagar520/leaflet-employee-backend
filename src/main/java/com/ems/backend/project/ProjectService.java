package com.ems.backend.project;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.media.MediaAsset;
import com.ems.backend.media.MediaAttachmentService;
import com.ems.backend.media.UploadPurpose;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.project.dto.*;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.task.Task;
import com.ems.backend.task.TaskRepository;
import com.ems.backend.task.TaskStatus;
import com.ems.backend.task.TaskCommentRepository;
import com.ems.backend.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final ProjectPaymentRepository projectPaymentRepository;
    private final ProjectNoteRepository projectNoteRepository;
    private final ProjectMilestoneRepository projectMilestoneRepository;
    private final ProjectTaskBoardRepository projectTaskBoardRepository;
    private final SecurityUtils securityUtils;
    private final ProjectAccessService projectAccessService;
    private final NotificationService notificationService;
    private final SecurityAuditService auditService;
    private final AuthorizationPolicyService authorizationPolicy;
    private final ProjectNoteMediaAttachmentRepository noteMediaAttachmentRepository;
    private final MediaAttachmentService mediaAttachmentService;
    private final TaskCommentRepository taskCommentRepository;
    private final ProjectTransitionPolicy transitionPolicy;
    private final BusinessClock businessClock;

    public ProjectService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            TaskRepository taskRepository,
            ProjectPaymentRepository projectPaymentRepository,
            ProjectNoteRepository projectNoteRepository,
            ProjectMilestoneRepository projectMilestoneRepository,
            ProjectTaskBoardRepository projectTaskBoardRepository,
            SecurityUtils securityUtils,
            ProjectAccessService projectAccessService,
            NotificationService notificationService,
            SecurityAuditService auditService,
            AuthorizationPolicyService authorizationPolicy,
            ProjectNoteMediaAttachmentRepository noteMediaAttachmentRepository,
            MediaAttachmentService mediaAttachmentService,
            TaskCommentRepository taskCommentRepository,
            ProjectTransitionPolicy transitionPolicy,
            BusinessClock businessClock
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.projectPaymentRepository = projectPaymentRepository;
        this.projectNoteRepository = projectNoteRepository;
        this.projectMilestoneRepository = projectMilestoneRepository;
        this.projectTaskBoardRepository = projectTaskBoardRepository;
        this.securityUtils = securityUtils;
        this.projectAccessService = projectAccessService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.authorizationPolicy = authorizationPolicy;
        this.noteMediaAttachmentRepository = noteMediaAttachmentRepository;
        this.mediaAttachmentService = mediaAttachmentService;
        this.taskCommentRepository = taskCommentRepository;
        this.transitionPolicy = transitionPolicy;
        this.businessClock = businessClock;
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
        project.setBudgetAmount(request.budgetAmount() != null ? request.budgetAmount() : BigDecimal.ZERO);
        project.setInternalNotes(request.internalNotes());

        Project saved = projectRepository.saveAndFlush(project);
        if (request.documentMediaAssetId() != null) {
            MediaAsset document = mediaAttachmentService.attach(
                    request.documentMediaAssetId(),
                    UploadPurpose.PROJECT_ATTACHMENT,
                    currentUser,
                    currentUser,
                    "PROJECT",
                    saved.getId().toString()
            );
            saved.setDocumentMediaAsset(document);
            saved.setDocumentUrl(null);
            saved.setDocumentLegacyStatus("NONE");
            saved = projectRepository.save(saved);
        }
        createDefaultTaskBoards(saved);

        if (request.assignedEmployeeIds() != null) {
            Set<User> employees = request.assignedEmployeeIds().stream()
                    .map(this::getUserById)
                    .peek(employee -> requireAssignableProjectMember(currentUser, employee))
                    .collect(Collectors.toSet());
            saved.setAssignedEmployees(employees);
            notifyNewlyAssignedEmployees(saved, Set.of(), employees);
            saved = projectRepository.save(saved);
            projectRepository.flush();
            applyMemberPermissions(saved.getId(), employees, request.memberPermissions());
        }

        return mapForUser(saved, currentUser);
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

    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjectsForStaff(Long userId) {
        User currentUser = getCurrentUser();
        return projectRepository.findAllForStaffMember(userId).stream()
                .filter(project -> currentUser.getRole() == Role.ADMIN || projectAccessService.canAccessProject(currentUser, project))
                .map(project -> mapForUser(project, currentUser))
                .toList();
    }

    public ProjectResponse updateProject(Long projectId, UpdateProjectRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);
        transitionPolicy.requireMutable(project);

        User manager = getUserById(request.managerId());
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Manager must have MANAGER or ADMIN role");
        }
        if (currentUser.getRole() == Role.MANAGER && !manager.getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Managers cannot transfer projects to another manager");
        }

        ProjectStatus previousStatus = project.getStatus();
        transitionPolicy.requireTransition(previousStatus, request.status());
        project.setName(request.name());
        project.setDescription(request.description());
        project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setDueDate(request.dueDate());
        project.setManager(manager);
        project.setClientNotes(request.clientNotes());
        if (request.documentMediaAssetId() != null
                && (project.getDocumentMediaAsset() == null
                || !request.documentMediaAssetId().equals(
                        project.getDocumentMediaAsset().getId()
                ))) {
            MediaAsset previous = project.getDocumentMediaAsset();
            MediaAsset document = mediaAttachmentService.attach(
                    request.documentMediaAssetId(),
                    UploadPurpose.PROJECT_ATTACHMENT,
                    currentUser,
                    currentUser,
                    "PROJECT",
                    project.getId().toString()
            );
            project.setDocumentMediaAsset(document);
            project.setDocumentUrl(null);
            project.setDocumentLegacyStatus("NONE");
            projectRepository.saveAndFlush(project);
            mediaAttachmentService.deleteAttached(
                    previous, currentUser, "PROJECT_DOCUMENT_REPLACED"
            );
        }
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
                    .peek(employee -> requireAssignableProjectMember(currentUser, employee))
                    .collect(Collectors.toSet());
            project.setAssignedEmployees(employees);
            notifyNewlyAssignedEmployees(project, previousIds, employees);
            projectRepository.save(project);
            projectRepository.flush();
            applyMemberPermissions(project.getId(), employees, request.memberPermissions());
        }

        Project saved = projectRepository.save(project);
        if (previousStatus != saved.getStatus()) {
            auditService.recordWithDetails(
                    currentUser.getId(),
                    saved.getManager().getId(),
                    saved.getStatus() == ProjectStatus.COMPLETED ? "PROJECT_COMPLETED" : "PROJECT_STATUS_CHANGED",
                    "SUCCESS",
                    "AUTHORIZED_PROJECT_MANAGER",
                    "projectId=" + saved.getId() + ",from=" + previousStatus + ",to=" + saved.getStatus(),
                    saved.getManager().getEmail(),
                    RequestMetadata.current()
            );
        }
        return mapForUser(saved, currentUser);
    }

    public void deleteProject(Long projectId) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);
        mediaAttachmentService.deleteAttached(
                project.getDocumentMediaAsset(), currentUser, "PARENT_PROJECT_DELETED"
        );
        projectNoteRepository.findAllByProjectIdWithCreatorOrderByCreatedAtDesc(projectId)
                .forEach(note -> noteMediaAttachmentRepository
                        .findByNoteIdOrderByDisplayOrder(note.getId())
                        .forEach(link -> mediaAttachmentService.deleteAttached(
                                link.getMediaAsset(), currentUser, "PARENT_PROJECT_DELETED"
                        )));
        projectPaymentRepository.findAllByProjectIdWithCreatorOrderByPaidAtDesc(projectId)
                .forEach(payment -> payment.getAttachments().forEach(
                        attachment -> mediaAttachmentService.deleteAttached(
                                attachment.getMediaAsset(), currentUser, "PARENT_PROJECT_DELETED"
                        )
                ));
        taskRepository.findByProjectId(projectId).forEach(task ->
                taskCommentRepository.findByTaskIdOrderByCreatedAtDesc(task.getId())
                        .forEach(comment -> mediaAttachmentService.deleteAttached(
                                comment.getMediaAsset(), currentUser, "PARENT_PROJECT_DELETED"
                        ))
        );
        projectRepository.deleteById(projectId);
    }

    public List<ProjectTaskBoardResponse> listTaskBoards(Long projectId) {
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(projectId, currentUser);
        ensureDefaultTaskBoards(projectId);
        return projectTaskBoardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(projectId)
                .stream()
                .map(this::mapTaskBoard)
                .toList();
    }

    public ProjectTaskBoardResponse createTaskBoard(Long projectId, CreateProjectTaskBoardRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);
        transitionPolicy.requireMutable(project);
        ensureDefaultTaskBoards(projectId);

        String name = request.name().trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Board name is required");
        }
        if (projectTaskBoardRepository.existsByProjectIdAndNameIgnoreCase(projectId, name)) {
            throw new ResponseStatusException(BAD_REQUEST, "A board with this name already exists");
        }

        String baseKey = slugStatusKey(name);
        String statusKey = baseKey;
        int suffix = 2;
        while (projectTaskBoardRepository.existsByProjectIdAndStatusKey(projectId, statusKey)) {
            statusKey = baseKey + "_" + suffix++;
        }

        ProjectTaskBoard board = new ProjectTaskBoard();
        board.setProject(project);
        board.setName(name);
        board.setStatusKey(statusKey);
        board.setDisplayOrder((projectTaskBoardRepository.countByProjectId(projectId) + 1) * 10);
        board.setDefaultBoard(false);
        board.setTerminal(false);
        return mapTaskBoard(projectTaskBoardRepository.save(board));
    }

    public List<ProjectTaskBoardResponse> reorderTaskBoards(Long projectId, ReorderProjectTaskBoardsRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireManageableProject(projectId, currentUser);
        transitionPolicy.requireMutable(project);
        ensureDefaultTaskBoards(projectId);

        List<ProjectTaskBoard> boards = projectTaskBoardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(projectId);
        List<Long> currentIds = boards.stream().map(ProjectTaskBoard::getId).toList();
        if (request.boardIds().size() != boards.size() || !request.boardIds().containsAll(currentIds)) {
            throw new ResponseStatusException(BAD_REQUEST, "Board order must include every board in this project");
        }

        for (int i = 0; i < request.boardIds().size(); i++) {
            Long boardId = request.boardIds().get(i);
            ProjectTaskBoard board = boards.stream()
                    .filter(item -> item.getId().equals(boardId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Invalid board order"));
            board.setDisplayOrder((i + 1) * 10);
        }
        projectTaskBoardRepository.saveAll(boards);
        return projectTaskBoardRepository.findByProjectIdOrderByDisplayOrderAscIdAsc(projectId)
                .stream()
                .map(this::mapTaskBoard)
                .toList();
    }

    public List<PaymentResponse> listPayments(Long projectId) {
        User currentUser = getCurrentUser();
        projectAccessService.requireFinanciallyVisibleProject(projectId, currentUser);
        return projectPaymentRepository.findAllByProjectIdWithCreatorOrderByPaidAtDesc(projectId).stream()
                .map(this::mapPayment)
                .toList();
    }

    public PaymentResponse addPayment(Long projectId, CreatePaymentRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requirePaymentManageableProject(projectId, currentUser);
        if (request.idempotencyKey() != null) {
            ProjectPayment existing = projectPaymentRepository
                    .findByProjectIdAndIdempotencyKey(projectId, request.idempotencyKey())
                    .orElse(null);
            if (existing != null) {
                return mapPayment(existing);
            }
        }
        if (request.paidAt().isAfter(businessClock.now().plusSeconds(300))) {
            throw new ResponseStatusException(BAD_REQUEST, "Payment date cannot be in the future");
        }
        if (project.getStartDate() != null
                && request.paidAt().atZone(businessClock.zoneId()).toLocalDate().isBefore(project.getStartDate())) {
            throw new ResponseStatusException(BAD_REQUEST, "Payment date cannot be before the project start date");
        }
        ProjectPayment payment = new ProjectPayment();
        payment.setProject(project);
        payment.setAmount(request.amount());
        payment.setPaidAt(request.paidAt());
        payment.setReferenceNote(request.referenceNote());
        payment.setIdempotencyKey(request.idempotencyKey());
        payment.setCreatedBy(currentUser);
        projectPaymentRepository.saveAndFlush(payment);
        replacePaymentAttachments(payment, request.attachments());
        ProjectPayment saved = projectPaymentRepository.save(payment);
        auditPayment(currentUser, project, saved, "PROJECT_PAYMENT_CREATED");
        return mapPayment(saved);
    }

    public PaymentResponse updatePaymentAttachments(
            Long projectId,
            Long paymentId,
            UpdatePaymentAttachmentsRequest request
    ) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requirePaymentManageableProject(projectId, currentUser);
        ProjectPayment payment = projectPaymentRepository.findByIdAndProjectIdForAttachmentUpdate(paymentId, projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Payment not found"));
        replacePaymentAttachments(payment, request.attachments());
        ProjectPayment saved = projectPaymentRepository.save(payment);
        auditPayment(currentUser, project, saved, "PROJECT_PAYMENT_ATTACHMENTS_UPDATED");
        return mapPayment(saved);
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
        ProjectNote saved = projectNoteRepository.saveAndFlush(note);
        int order = 0;
        for (UUID mediaAssetId : request.mediaAssetIds()) {
            MediaAsset asset = mediaAttachmentService.attach(
                    mediaAssetId,
                    UploadPurpose.PROJECT_ATTACHMENT,
                    currentUser,
                    currentUser,
                    "PROJECT_NOTE",
                    saved.getId().toString()
            );
            ProjectNoteMediaAttachment attachment = new ProjectNoteMediaAttachment();
            attachment.setNote(saved);
            attachment.setMediaAsset(asset);
            attachment.setDisplayOrder(order++);
            noteMediaAttachmentRepository.save(attachment);
        }
        return mapNote(saved);
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
        noteMediaAttachmentRepository.findByNoteIdOrderByDisplayOrder(noteId)
                .forEach(attachment -> mediaAttachmentService.deleteAttached(
                        attachment.getMediaAsset(), currentUser, "PROJECT_NOTE_DELETED"
                ));
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
        List<ProjectNoteResponse.Attachment> attachments =
                noteMediaAttachmentRepository.findByNoteIdOrderByDisplayOrder(note.getId())
                        .stream()
                        .map(link -> {
                            MediaAsset asset = link.getMediaAsset();
                            return new ProjectNoteResponse.Attachment(
                                    asset.getId(),
                                    asset.getOriginalFilename(),
                                    asset.getDetectedMimeType(),
                                    asset.getSizeBytes(),
                                    "/api/v1/media/assets/" + asset.getId() + "/download"
                            );
                        })
                        .toList();
        return new ProjectNoteResponse(
                note.getId(),
                note.getContent(),
                note.getNoteType(),
                note.getCreatedBy().getFullName(),
                note.getCreatedAt(),
                attachments,
                note.getLegacyAttachmentStatus()
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
                                attachment.getMediaAsset() == null
                                        ? null : attachment.getMediaAsset().getId(),
                                attachment.getMediaAsset() == null
                                        ? null : "/api/v1/media/assets/"
                                        + attachment.getMediaAsset().getId() + "/download",
                                attachment.getFileName(),
                                attachment.getFileType(),
                                attachment.getLegacyAssetStatus()
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
        Set<UUID> requestedIds = attachments.stream()
                .map(PaymentAttachmentRequest::mediaAssetId)
                .collect(Collectors.toCollection(HashSet::new));
        if (requestedIds.size() != attachments.size()) {
            throw new ResponseStatusException(BAD_REQUEST, "Duplicate payment attachments are not allowed");
        }
        User actor = getCurrentUser();
        Map<UUID, ProjectPaymentAttachment> existing = payment.getAttachments().stream()
                .filter(value -> value.getMediaAsset() != null)
                .collect(Collectors.toMap(
                        value -> value.getMediaAsset().getId(),
                        value -> value
                ));
        payment.getAttachments().removeIf(value -> {
            UUID mediaId = value.getMediaAsset() == null ? null : value.getMediaAsset().getId();
            if (mediaId != null && requestedIds.contains(mediaId)) {
                return false;
            }
            mediaAttachmentService.deleteAttached(
                    value.getMediaAsset(), actor, "PAYMENT_ATTACHMENT_REMOVED"
            );
            return true;
        });
        for (PaymentAttachmentRequest request : attachments) {
            if (existing.containsKey(request.mediaAssetId())) {
                continue;
            }
            MediaAsset asset = mediaAttachmentService.attach(
                    request.mediaAssetId(),
                    UploadPurpose.PAYMENT_ATTACHMENT,
                    actor,
                    actor,
                    "PROJECT_PAYMENT",
                    payment.getId().toString()
            );
            ProjectPaymentAttachment attachment = new ProjectPaymentAttachment();
            attachment.setPayment(payment);
            attachment.setMediaAsset(asset);
            attachment.setFileUrl(null);
            attachment.setFileName(asset.getOriginalFilename());
            attachment.setFileType(asset.getDetectedMimeType());
            attachment.setLegacyAssetStatus("NONE");
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

    private void requireAssignableProjectMember(User actor, User employee) {
        if (!Boolean.TRUE.equals(employee.getActive()) || employee.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Project members must be active employees or managers"
            );
        }
        if (actor.getRole() == Role.MANAGER
                && !authorizationPolicy.canViewEmployeeDirectoryEntry(actor, employee)) {
            throw new ResponseStatusException(
                    FORBIDDEN,
                    "Managers may assign only employees in their reporting scope"
            );
        }
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
        boolean canViewFinancials =
                projectAccessService.canViewProjectFinancials(viewer, project);
        return map(project, viewer, canViewFinancials);
    }

    private ProjectResponse map(Project project, User viewer, boolean includeFinancials) {
        List<Task> tasks = taskRepository.findByProjectId(project.getId());
        int progress = 0;
        if (!tasks.isEmpty()) {
            long doneCount = tasks.stream().filter(t -> TaskStatus.DONE.name().equals(t.getStatus())).count();
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
                project.getDocumentMediaAsset() == null
                        ? null : project.getDocumentMediaAsset().getId(),
                project.getDocumentMediaAsset() == null
                        ? null : "/api/v1/media/assets/"
                        + project.getDocumentMediaAsset().getId() + "/download",
                project.getDocumentLegacyStatus(),
                includeFinancials ? project.getBudgetAmount() : null,
                includeFinancials ? totalPaid : null,
                lastAmount,
                lastAt,
                lastNote,
                projectAccessService.canManageProject(viewer, project),
                includeFinancials,
                projectAccessService.canRecordProjectPayment(viewer, project),
                progress,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private void auditPayment(
            User actor,
            Project project,
            ProjectPayment payment,
            String eventType
    ) {
        auditService.recordWithDetails(
                actor.getId(),
                null,
                eventType,
                "SUCCESS",
                "PROJECT_FINANCIAL_OPERATION",
                "projectId=%d,paymentId=%d".formatted(project.getId(), payment.getId()),
                null,
                RequestMetadata.current()
        );
    }

    private void ensureDefaultTaskBoards(Long projectId) {
        if (projectTaskBoardRepository.countByProjectId(projectId) > 0) {
            return;
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Project not found"));
        createDefaultTaskBoards(project);
    }

    private void createDefaultTaskBoards(Project project) {
        if (projectTaskBoardRepository.existsByProjectIdAndStatusKey(project.getId(), TaskStatus.TODO.name())) {
            return;
        }
        createDefaultTaskBoard(project, TaskStatus.TODO.name(), "To Do", 10, false);
        createDefaultTaskBoard(project, TaskStatus.IN_PROGRESS.name(), "In Progress", 20, false);
        createDefaultTaskBoard(project, TaskStatus.BLOCKED.name(), "Blocked", 30, false);
        createDefaultTaskBoard(project, TaskStatus.DONE.name(), "Done", 40, true);
    }

    private void createDefaultTaskBoard(Project project, String statusKey, String name, int displayOrder, boolean terminal) {
        ProjectTaskBoard board = new ProjectTaskBoard();
        board.setProject(project);
        board.setStatusKey(statusKey);
        board.setName(name);
        board.setDisplayOrder(displayOrder);
        board.setDefaultBoard(true);
        board.setTerminal(terminal);
        projectTaskBoardRepository.save(board);
    }

    private String slugStatusKey(String name) {
        String slug = name.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            slug = "BOARD";
        }
        if (!slug.startsWith("CUSTOM_")) {
            slug = "CUSTOM_" + slug;
        }
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("_+$", "") : slug;
    }

    private ProjectTaskBoardResponse mapTaskBoard(ProjectTaskBoard board) {
        return new ProjectTaskBoardResponse(
                board.getId(),
                board.getProject().getId(),
                board.getStatusKey(),
                board.getName(),
                board.getDisplayOrder(),
                board.isDefaultBoard(),
                board.isTerminal()
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
