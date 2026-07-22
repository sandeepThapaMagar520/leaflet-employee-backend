package com.ems.backend.task;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.notification.EventIds;
import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectStatus;
import com.ems.backend.project.ProjectTaskBoardRepository;
import com.ems.backend.media.MediaAsset;
import com.ems.backend.media.MediaAttachmentService;
import com.ems.backend.media.UploadPurpose;
import com.ems.backend.task.dto.CreateTaskRequest;
import com.ems.backend.task.dto.CreateTaskCommentRequest;
import com.ems.backend.task.dto.TaskCommentResponse;
import com.ems.backend.task.dto.TaskResponse;
import com.ems.backend.task.dto.UpdateTaskRequest;
import com.ems.backend.task.dto.UpdateTaskStatusRequest;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class TaskService {
    private final TaskRepository taskRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final ProjectAccessService projectAccessService;
    private final NotificationService notificationService;
    private final ProjectTaskBoardRepository projectTaskBoardRepository;
    private final MediaAttachmentService mediaAttachmentService;
    private final BusinessClock businessClock;
    private final TaskTransitionPolicy transitionPolicy;
    private final SecurityAuditService auditService;

    public TaskService(
            TaskRepository taskRepository,
            TaskCommentRepository taskCommentRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            ProjectAccessService projectAccessService,
            NotificationService notificationService,
            ProjectTaskBoardRepository projectTaskBoardRepository,
            MediaAttachmentService mediaAttachmentService,
            BusinessClock businessClock,
            TaskTransitionPolicy transitionPolicy,
            SecurityAuditService auditService
    ) {
        this.taskRepository = taskRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.projectAccessService = projectAccessService;
        this.notificationService = notificationService;
        this.projectTaskBoardRepository = projectTaskBoardRepository;
        this.mediaAttachmentService = mediaAttachmentService;
        this.businessClock = businessClock;
        this.transitionPolicy = transitionPolicy;
        this.auditService = auditService;
    }

    public TaskResponse createTask(CreateTaskRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireTaskManageableProject(request.projectId(), currentUser);
        requireMutableProject(project);
        User assignee = getUserById(request.assignedToId());
        requireAssignableTaskUser(assignee);
        requireProjectTeamMember(project, assignee);
        validateDueDate(request.dueDate());

        Task task = new Task();
        task.setTitle(request.title());
        task.setDescription(request.description());
        requireProjectBoard(project, TaskStatus.TODO.name());
        task.setStatus(TaskStatus.TODO.name());
        task.setPriority(request.priority());
        task.setDueDate(request.dueDate());
        task.setProject(project);
        task.setAssignedTo(assignee);
        task.setCreatedBy(currentUser);

        Task saved = taskRepository.save(task);
        notificationService.notifyUserEvent(
                EventIds.stable("TASK_ASSIGNED", saved.getId(), assignee.getId(), saved.getVersion()),
                "TASK_ASSIGNED",
                assignee,
                NotificationType.TASK_ASSIGNED,
                "New task assigned",
                "You were assigned \"" + saved.getTitle() + "\" on " + project.getName(),
                "/tasks",
                true,
                java.util.Map.of("resourceId", saved.getId())
        );
        return map(saved);
    }

    public List<TaskResponse> getAllTasks() {
        User currentUser = getCurrentUser();
        List<Task> tasks = switch (currentUser.getRole()) {
            case ADMIN -> taskRepository.findAllWithDetails();
            case MANAGER -> taskRepository.findByProjectManagerId(currentUser.getId());
            case EMPLOYEE -> taskRepository.findByAssignedToEmailIgnoreCase(currentUser.getEmail());
        };
        return tasks.stream().map(this::map).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> getAllTasksPaged(int page, int size, String sortBy, String sortDir) {
        User currentUser = getCurrentUser();
        var pageable = Pagination.page(page, size, sortBy, sortDir,
                Set.of("createdAt", "updatedAt", "dueDate", "priority", "status", "title"));
        var tasks = switch (currentUser.getRole()) {
            case ADMIN -> taskRepository.findAllWithDetails(pageable);
            case MANAGER -> taskRepository.findByProjectManagerId(currentUser.getId(), pageable);
            case EMPLOYEE -> taskRepository.findByAssignedToIdWithDetails(currentUser.getId(), pageable);
        };
        return PageResponse.from(tasks, this::map);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> getTasksByProject(Long projectId, int page, int size, String sortBy, String sortDir) {
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(projectId, currentUser);
        var pageable = Pagination.page(page, size, sortBy, sortDir,
                Set.of("createdAt", "updatedAt", "dueDate", "priority", "status", "title"));
        return PageResponse.from(taskRepository.findByProjectId(projectId, pageable), this::map);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> getMyTasks(int page, int size, String sortBy, String sortDir) {
        Long userId = getCurrentUser().getId();
        var pageable = Pagination.page(page, size, sortBy, sortDir,
                Set.of("createdAt", "updatedAt", "dueDate", "priority", "status", "title"));
        return PageResponse.from(taskRepository.findByAssignedToIdWithDetails(userId, pageable), this::map);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksByAssignee(Long userId) {
        return taskRepository.findByAssignedToIdWithDetails(userId).stream().map(this::map).toList();
    }

    public TaskResponse updateTask(Long taskId, UpdateTaskRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireTaskManageableProject(task.getProject().getId(), currentUser);
        requireMutableProject(project);
        User assignee = getUserById(request.assignedToId());
        Long previousAssigneeId = task.getAssignedTo().getId();

        requireAssignableTaskUser(assignee);
        requireProjectTeamMember(project, assignee);
        validateDueDate(request.dueDate());

        transitionPolicy.requireTransition(project, task.getStatus(), request.status(), true);
        String previousStatus = task.getStatus();
        task.setTitle(request.title());
        task.setDescription(request.description());
        requireProjectBoard(project, request.status());
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setDueDate(request.dueDate());
        task.setAssignedTo(assignee);

        Task saved = taskRepository.save(task);
        auditStatusChange(currentUser, saved, previousStatus);
        if (!previousAssigneeId.equals(assignee.getId())) {
            notificationService.notifyUserEvent(
                    EventIds.stable("TASK_REASSIGNED", saved.getId(), assignee.getId(), saved.getVersion()),
                    "TASK_REASSIGNED",
                    assignee,
                    NotificationType.TASK_ASSIGNED,
                    "Task reassigned to you",
                    "\"" + saved.getTitle() + "\" on " + saved.getProject().getName(),
                    "/tasks",
                    true,
                    java.util.Map.of("resourceId", saved.getId())
            );
        }
        return map(saved);
    }

    public TaskResponse updateTaskStatus(Long taskId, UpdateTaskStatusRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        Project project =
                projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);
        requireMutableProject(project);

        boolean ownsTask = task.getAssignedTo().getId().equals(currentUser.getId());
        if (!ownsTask && !projectAccessService.canManageTasks(currentUser, project)) {
            throw new ResponseStatusException(
                    FORBIDDEN,
                    "You can update only your own tasks unless project task management is granted"
            );
        }

        requireProjectBoard(task.getProject(), request.status());
        String previousStatus = task.getStatus();
        transitionPolicy.requireTransition(
                project,
                previousStatus,
                request.status(),
                projectAccessService.canManageTasks(currentUser, project)
        );
        task.setStatus(request.status());
        Task saved = taskRepository.save(task);
        auditStatusChange(currentUser, saved, previousStatus);

        if (!TaskStatus.DONE.name().equals(previousStatus) && TaskStatus.DONE.name().equals(saved.getStatus())
                && !saved.getCreatedBy().getId().equals(currentUser.getId())) {
            notificationService.notifyUserEvent(
                    EventIds.stable("TASK_COMPLETED", saved.getId(), saved.getVersion()),
                    "TASK_COMPLETED",
                    saved.getCreatedBy(),
                    NotificationType.TASK_COMPLETED,
                    "Task completed",
                    currentUser.getFullName() + " completed \"" + saved.getTitle() + "\"",
                    "/tasks",
                    true
            );
        }
        return map(saved);
    }

    public void deleteTask(Long taskId) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireTaskManageableProject(task.getProject().getId(), currentUser);
        requireMutableProject(project);
        taskCommentRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .forEach(comment -> mediaAttachmentService.deleteAttached(
                        comment.getMediaAsset(), currentUser, "PARENT_TASK_DELETED"
                ));
        taskRepository.delete(task);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskCommentResponse> getTaskComments(Long taskId, int page, int size) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);
        var pageable = Pagination.page(page, size, "createdAt", "desc", Set.of("createdAt"));
        return PageResponse.from(taskCommentRepository.findByTaskId(taskId, pageable), this::mapComment);
    }

    public TaskCommentResponse addTaskComment(Long taskId, CreateTaskCommentRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);
        requireMutableProject(task.getProject());

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(currentUser);
        comment.setContent(request.content());
        TaskComment saved = taskCommentRepository.saveAndFlush(comment);
        if (request.mediaAssetId() != null) {
            MediaAsset asset = mediaAttachmentService.attach(
                    request.mediaAssetId(),
                    UploadPurpose.TASK_ATTACHMENT,
                    currentUser,
                    currentUser,
                    "TASK_COMMENT",
                    saved.getId().toString()
            );
            saved.setMediaAsset(asset);
            saved.setAttachmentUrl(null);
            saved.setAttachmentName(asset.getOriginalFilename());
            saved.setLegacyAssetStatus("NONE");
            saved = taskCommentRepository.save(saved);
        }

        Set<User> mentionedUsers = resolveMentionedUsers(task.getProject(), request.mentionedUserIds(), currentUser);
        for (User mentionedUser : mentionedUsers) {
            notificationService.notifyUserEvent(
                    EventIds.stable("TASK_MENTION", saved.getId(), mentionedUser.getId()),
                    "TASK_MENTION",
                    mentionedUser,
                    NotificationType.TASK_COMMENTED,
                    "You were mentioned",
                    currentUser.getFullName() + " mentioned you on \"" + task.getTitle() + "\"",
                    "/projects/" + task.getProject().getId(),
                    true
            );
        }

        boolean assigneeAlreadyMentioned = mentionedUsers.stream()
                .anyMatch(user -> user.getId().equals(task.getAssignedTo().getId()));
        if (!assigneeAlreadyMentioned && !task.getAssignedTo().getId().equals(currentUser.getId())) {
            notificationService.notifyUserEvent(
                    EventIds.stable("TASK_COMMENT", saved.getId(), task.getAssignedTo().getId()),
                    "TASK_COMMENT",
                    task.getAssignedTo(),
                    NotificationType.TASK_COMMENTED,
                    "New task comment",
                    currentUser.getFullName() + " commented on \"" + task.getTitle() + "\"",
                    "/projects/" + task.getProject().getId(),
                    true
            );
        }

        return mapComment(saved);
    }

    private Task getTaskById(Long taskId) {
        return taskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Task not found"));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }

    private User getCurrentUser() {
        return securityUtils.getCurrentUser();
    }

    private void requireProjectTeamMember(Project project, User assignee) {
        boolean isProjectManager = project.getManager().getId().equals(assignee.getId());
        boolean isAssignedEmployee = project.getAssignedEmployees().stream()
                .anyMatch(employee -> employee.getId().equals(assignee.getId()));
        if (!isProjectManager && !isAssignedEmployee) {
            throw new ResponseStatusException(BAD_REQUEST, "Task assignee must be a member of this project team");
        }
    }

    private void requireAssignableTaskUser(User assignee) {
        if (assignee.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Tasks can be assigned only to employees or managers");
        }
    }

    private void validateDueDate(LocalDate dueDate) {
        if (dueDate != null && dueDate.isBefore(businessClock.today())) {
            throw new ResponseStatusException(BAD_REQUEST, "Due date must be today or a future date");
        }
    }

    private void requireMutableProject(Project project) {
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Tasks on a completed project are read-only"
            );
        }
    }

    private void auditStatusChange(User actor, Task task, String previousStatus) {
        if (previousStatus.equals(task.getStatus())) return;
        String eventType = "DONE".equals(task.getStatus())
                ? "TASK_COMPLETED"
                : ("DONE".equals(previousStatus) ? "TASK_REOPENED" : "TASK_STATUS_CHANGED");
        auditService.recordWithDetails(
                actor.getId(),
                task.getAssignedTo().getId(),
                eventType,
                "SUCCESS",
                "AUTHORIZED_TASK_TRANSITION",
                "taskId=" + task.getId() + ",from=" + previousStatus + ",to=" + task.getStatus(),
                task.getAssignedTo().getEmail(),
                RequestMetadata.current()
        );
    }

    private void requireProjectBoard(Project project, String status) {
        String statusKey = status == null ? "" : status.trim();
        if (statusKey.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Task board is required");
        }
        if (!projectTaskBoardRepository.existsByProjectIdAndStatusKey(project.getId(), statusKey)) {
            throw new ResponseStatusException(BAD_REQUEST, "Task board does not exist for this project");
        }
    }

    private Set<User> resolveMentionedUsers(Project project, List<Long> mentionedUserIds, User currentUser) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> uniqueIds = new LinkedHashSet<>(mentionedUserIds);
        uniqueIds.remove(currentUser.getId());

        Set<User> mentionedUsers = new LinkedHashSet<>();
        for (Long userId : uniqueIds) {
            User user = getUserById(userId);
            requireProjectTeamMember(project, user);
            mentionedUsers.add(user);
        }
        return mentionedUsers;
    }

    private TaskResponse map(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getDueDate(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getAssignedTo().getId(),
                task.getAssignedTo().getFullName(),
                task.getCreatedBy().getId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskCommentResponse mapComment(TaskComment comment) {
        return new TaskCommentResponse(
                comment.getId(),
                comment.getTask().getId(),
                comment.getUser().getId(),
                comment.getUser().getFullName(),
                comment.getContent(),
                comment.getMediaAsset() == null ? null : comment.getMediaAsset().getId(),
                comment.getMediaAsset() == null
                        ? null : "/api/v1/media/assets/" + comment.getMediaAsset().getId() + "/download",
                comment.getAttachmentName(),
                comment.getLegacyAssetStatus(),
                comment.getCreatedAt()
        );
    }
}
