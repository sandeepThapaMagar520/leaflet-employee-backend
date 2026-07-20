package com.ems.backend.task;

import com.ems.backend.notification.NotificationService;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.time.BusinessClock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class TaskReminderService {
    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final BusinessClock businessClock;

    public TaskReminderService(
            TaskRepository taskRepository,
            NotificationService notificationService,
            BusinessClock businessClock
    ) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
        this.businessClock = businessClock;
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void sendDueDateReminders() {
        LocalDate today = businessClock.today();
        LocalDate tomorrow = today.plusDays(1);

        for (Task task : taskRepository.findByStatusNotAndDueDateIsNotNull(TaskStatus.DONE.name())) {
            if (task.getDueDate().isBefore(today)) {
                notificationService.notifyUser(
                        task.getAssignedTo(),
                        NotificationType.TASK_OVERDUE,
                        reminderTitle(task, "overdue"),
                        "\"" + task.getTitle() + "\" was due " + overdueDays(task, today) + " ago. Priority: " + task.getPriority() + ".",
                        "/projects/" + task.getProject().getId()
                );
            } else if (!task.getDueDate().isAfter(tomorrow)) {
                notificationService.notifyUser(
                        task.getAssignedTo(),
                        NotificationType.TASK_DUE_SOON,
                        reminderTitle(task, task.getDueDate().isEqual(today) ? "due today" : "due soon"),
                        "\"" + task.getTitle() + "\" is due " + dueLabel(task, today) + ". Priority: " + task.getPriority() + ".",
                        "/projects/" + task.getProject().getId()
                );
            }
        }
    }

    private String reminderTitle(Task task, String state) {
        return switch (task.getPriority()) {
            case CRITICAL -> "Critical task " + state;
            case HIGH -> "High priority task " + state;
            default -> "Task " + state;
        };
    }

    private String overdueDays(Task task, LocalDate today) {
        long days = ChronoUnit.DAYS.between(task.getDueDate(), today);
        return days == 1 ? "1 day" : days + " days";
    }

    private String dueLabel(Task task, LocalDate today) {
        if (task.getDueDate().isEqual(today)) {
            return "today";
        }
        return "tomorrow";
    }
}
