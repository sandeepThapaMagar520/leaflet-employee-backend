package com.ems.backend.notification;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.mail.EmailService;
import com.ems.backend.notification.dto.NotificationResponse;
import com.ems.backend.user.User;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final UserProfileService userProfileService;
    private final EmailService emailService;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            UserProfileService userProfileService,
            EmailService emailService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.userProfileService = userProfileService;
        this.emailService = emailService;
    }

    public void notifyUser(User user, NotificationType type, String title, String message, String link) {
        if (!persistNotification(user, type, title, message, link)) {
            return;
        }

        if (userProfileService.shouldEmailForNotification(user, type)) {
            emailService.sendNotificationEmail(user.getEmail(), type, title, message, link);
        }
    }

    /**
     * Persists a required in-app notification in the caller's transaction
     * without performing external email I/O inside that transaction.
     */
    public void notifyUserDatabaseOnly(
            User user,
            NotificationType type,
            String title,
            String message,
            String link
    ) {
        persistNotification(user, type, title, message, link);
    }

    private boolean persistNotification(
            User user,
            NotificationType type,
            String title,
            String message,
            String link
    ) {
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return false;
        }
        if (notificationRepository.existsByUser_IdAndTypeAndLinkAndTitle(user.getId(), type, link, title)) {
            return false;
        }
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLink(link);
        notificationRepository.save(notification);
        return true;
    }

    public void notifyUserId(Long userId, NotificationType type, String title, String message, String link) {
        userRepository.findById(userId).ifPresent(user -> notifyUser(user, type, title, message, link));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications() {
        User currentUser = securityUtils.getCurrentUser();
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(this::map)
                .toList();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount() {
        User currentUser = securityUtils.getCurrentUser();
        return notificationRepository.countByUserIdAndReadFalse(currentUser.getId());
    }

    public void markAsRead(Long notificationId) {
        User currentUser = securityUtils.getCurrentUser();
        int updated = notificationRepository.markReadForUser(notificationId, currentUser.getId());
        if (updated == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Notification not found");
        }
    }

    public void markAllAsRead() {
        User currentUser = securityUtils.getCurrentUser();
        notificationRepository.markAllReadForUser(currentUser.getId());
    }

    private NotificationResponse map(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getLink(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
