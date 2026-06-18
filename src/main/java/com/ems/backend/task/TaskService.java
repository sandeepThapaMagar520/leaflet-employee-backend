package com.ems.backend.task;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.project.Project;
import com.ems.backend.project.ProjectTaskBoardRepository;
import com.ems.backend.task.dto.CreateTaskRequest;
import com.ems.backend.task.dto.CreateTaskCommentRequest;
import com.ems.backend.task.dto.TaskCommentResponse;
import com.ems.backend.task.dto.TaskResponse;
import com.ems.backend.task.dto.UpdateTaskRequest;
import com.ems.backend.task.dto.UpdateTaskStatusRequest;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public TaskService(
            TaskRepository taskRepository,
            TaskCommentRepository taskCommentRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            ProjectAccessService projectAccessService,
            NotificationService notificationService,
            ProjectTaskBoardRepository projectTaskBoardRepository
    ) {
        this.taskRepository = taskRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.projectAccessService = projectAccessService;
        this.notificationService = notificationService;
        this.projectTaskBoardRepository = projectTaskBoardRepository;
    }

    public TaskResponse createTask(CreateTaskRequest request) {
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireTaskManageableProject(request.projectId(), currentUser);
        User assignee = getUserById(request.assignedToId());
        requireProjectTeamMember(project, assignee);

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
        notificationService.notifyUser(
                assignee,
                NotificationType.TASK_ASSIGNED,
                "New task assigned",
                "You were assigned \"" + saved.getTitle() + "\" on " + project.getName(),
                "/tasks"
        );
        return map(saved);
    }

    public List<TaskResponse> getAllTasks() {
        User currentUser = getCurrentUser();
        List<Task> tasks = switch (currentUser.getRole()) {
            case ADMIN -> taskRepository.findAll();
            case MANAGER -> taskRepository.findByProjectManagerId(currentUser.getId());
            case EMPLOYEE -> taskRepository.findByAssignedToEmailIgnoreCase(currentUser.getEmail());
        };
        return tasks.stream().map(this::map).toList();
    }

    public PageResponse<TaskResponse> getAllTasksPaged(int page, int size) {
        List<TaskResponse> all = getAllTasks();
        return PageResponse.of(all, page, size);
    }

    public List<TaskResponse> getTasksByProject(Long projectId) {
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(projectId, currentUser);
        return taskRepository.findByProjectId(projectId).stream().map(this::map).toList();
    }

    public List<TaskResponse> getMyTasks() {
        String email = securityUtils.getCurrentUserEmail();
        return taskRepository.findByAssignedToEmailIgnoreCase(email).stream().map(this::map).toList();
    }

    public TaskResponse updateTask(Long taskId, UpdateTaskRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        Project project = projectAccessService.requireTaskManageableProject(task.getProject().getId(), currentUser);
        User assignee = getUserById(request.assignedToId());
        Long previousAssigneeId = task.getAssignedTo().getId();

        requireProjectTeamMember(project, assignee);

        task.setTitle(request.title());
        task.setDescription(request.description());
        requireProjectBoard(project, request.status());
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setDueDate(request.dueDate());
        task.setAssignedTo(assignee);

        Task saved = taskRepository.save(task);
        if (!previousAssigneeId.equals(assignee.getId())) {
            notificationService.notifyUser(
                    assignee,
                    NotificationType.TASK_ASSIGNED,
                    "Task reassigned to you",
                    "\"" + saved.getTitle() + "\" on " + saved.getProject().getName(),
                    "/tasks"
            );
        }
        return map(saved);
    }

    public TaskResponse updateTaskStatus(Long taskId, UpdateTaskStatusRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);

        if (currentUser.getRole() == Role.EMPLOYEE && !task.getAssignedTo().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Employees can update only their own tasks");
        }

        requireProjectBoard(task.getProject(), request.status());
        String previousStatus = task.getStatus();
        task.setStatus(request.status());
        Task saved = taskRepository.save(task);

        if (!TaskStatus.DONE.name().equals(previousStatus) && TaskStatus.DONE.name().equals(saved.getStatus())
                && !saved.getCreatedBy().getId().equals(currentUser.getId())) {
            notificationService.notifyUser(
                    saved.getCreatedBy(),
                    NotificationType.TASK_COMPLETED,
                    "Task completed",
                    currentUser.getFullName() + " completed \"" + saved.getTitle() + "\"",
                    "/tasks"
            );
        }
        return map(saved);
    }

    public void deleteTask(Long taskId) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireTaskManageableProject(task.getProject().getId(), currentUser);
        taskRepository.delete(task);
    }

    @Transactional(readOnly = true)
    public List<TaskCommentResponse> getTaskComments(Long taskId) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);
        return taskCommentRepository.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(this::mapComment)
                .toList();
    }

    public TaskCommentResponse addTaskComment(Long taskId, CreateTaskCommentRequest request) {
        Task task = getTaskById(taskId);
        User currentUser = getCurrentUser();
        projectAccessService.requireAccessibleProject(task.getProject().getId(), currentUser);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(currentUser);
        comment.setContent(request.content());
        comment.setAttachmentUrl(request.attachmentUrl());
        comment.setAttachmentName(request.attachmentName());
        TaskComment saved = taskCommentRepository.save(comment);

        Set<User> mentionedUsers = resolveMentionedUsers(task.getProject(), request.mentionedUserIds(), currentUser);
        for (User mentionedUser : mentionedUsers) {
            notificationService.notifyUser(
                    mentionedUser,
                    NotificationType.TASK_COMMENTED,
                    "You were mentioned",
                    currentUser.getFullName() + " mentioned you on \"" + task.getTitle() + "\"",
                    "/projects/" + task.getProject().getId()
            );
        }

        boolean assigneeAlreadyMentioned = mentionedUsers.stream()
                .anyMatch(user -> user.getId().equals(task.getAssignedTo().getId()));
        if (!assigneeAlreadyMentioned && !task.getAssignedTo().getId().equals(currentUser.getId())) {
            notificationService.notifyUser(
                    task.getAssignedTo(),
                    NotificationType.TASK_COMMENTED,
                    "New task comment",
                    currentUser.getFullName() + " commented on \"" + task.getTitle() + "\"",
                    "/projects/" + task.getProject().getId()
            );
        }

        return mapComment(saved);
    }

    private Task getTaskById(Long taskId) {
        return taskRepository.findById(taskId)
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
                comment.getAttachmentUrl(),
                comment.getAttachmentName(),
                comment.getCreatedAt()
        );
    }
}
