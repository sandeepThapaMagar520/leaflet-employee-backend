package com.ems.backend.task;

import com.ems.backend.common.PageResponse;
import com.ems.backend.task.dto.CreateTaskCommentRequest;
import com.ems.backend.task.dto.CreateTaskRequest;
import com.ems.backend.task.dto.TaskCommentResponse;
import org.springframework.web.bind.annotation.RequestParam;
import com.ems.backend.task.dto.TaskResponse;
import com.ems.backend.task.dto.UpdateTaskRequest;
import com.ems.backend.task.dto.UpdateTaskStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Task assignment, status, and task comments")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Create task", description = "Admin and manager endpoint for assigning a task to an employee or manager.")
    public TaskResponse createTask(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "List managed tasks", description = "Admins see all tasks. Managers see tasks for their managed projects. Optional page and size parameters return a paged response.")
    public Object getAllTasks(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        if (page != null || size != null) {
            return taskService.getAllTasksPaged(page != null ? page : 0, size != null ? size : 20);
        }
        return taskService.getAllTasks();
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List tasks by project")
    public List<TaskResponse> getTasksByProject(@PathVariable Long projectId) {
        return taskService.getTasksByProject(projectId);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List my tasks")
    public List<TaskResponse> getMyTasks() {
        return taskService.getMyTasks();
    }

    @PutMapping("/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Update task", description = "Admin and manager endpoint for editing task details, priority, due date, assignee, and status.")
    public TaskResponse updateTask(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskRequest request
    ) {
        return taskService.updateTask(taskId, request);
    }

    @PatchMapping("/{taskId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Update task status", description = "Employees can update their own assigned tasks. Admin task-status submission is blocked by business rules.")
    public TaskResponse updateTaskStatus(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskStatusRequest request
    ) {
        return taskService.updateTaskStatus(taskId, request);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Delete task")
    public void deleteTask(@PathVariable Long taskId) {
        taskService.deleteTask(taskId);
    }

    @GetMapping("/{taskId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List task comments")
    public List<TaskCommentResponse> getTaskComments(@PathVariable Long taskId) {
        return taskService.getTaskComments(taskId);
    }

    @PostMapping("/{taskId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Add task comment", description = "Adds a comment and optional attachment reference to a task.")
    public TaskCommentResponse addTaskComment(
            @PathVariable Long taskId,
            @Valid @RequestBody CreateTaskCommentRequest request
    ) {
        return taskService.addTaskComment(taskId, request);
    }
}
