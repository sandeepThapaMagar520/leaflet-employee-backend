package com.ems.backend.task;

import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class TaskReminderService {
    private final TaskRepository taskRepository;
    private final NotificationService notificationService;

    public TaskReminderService(TaskRepository taskRepository, NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void sendDueDateReminders() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        for (Task task : taskRepository.findByStatusNotAndDueDateIsNotNull(TaskStatus.DONE)) {
            if (task.getDueDate().isBefore(today)) {
                notificationService.notifyUser(
                        task.getAssignedTo(),
                        NotificationType.TASK_OVERDUE,
                        "Task overdue",
                        "\"" + task.getTitle() + "\" was due on " + task.getDueDate(),
                        "/projects/" + task.getProject().getId()
                );
            } else if (!task.getDueDate().isAfter(tomorrow)) {
                notificationService.notifyUser(
                        task.getAssignedTo(),
                        NotificationType.TASK_DUE_SOON,
                        "Task due soon",
                        "\"" + task.getTitle() + "\" is due on " + task.getDueDate(),
                        "/projects/" + task.getProject().getId()
                );
            }
        }
    }
}
