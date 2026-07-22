package com.ems.backend.notification;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.outbox.DeliveryStatus;
import com.ems.backend.outbox.OutboxEnqueueRequest;
import com.ems.backend.outbox.OutboxService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.notification.dto.NotificationResponse;
import com.ems.backend.user.User;
import com.ems.backend.user.UserProfileService;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final UserProfileService userProfileService;
    private final OutboxService outboxService;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            UserProfileService userProfileService,
            OutboxService outboxService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.userProfileService = userProfileService;
        this.outboxService = outboxService;
    }

    public void notifyUser(User user, NotificationType type, String title, String message, String link) {
        notifyUserEvent(UUID.randomUUID(), type.name(), user, type, title, message, link, true);
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
        notifyUserEvent(UUID.randomUUID(), type.name(), user, type, title, message, link, false);
    }

    public DeliveryStatus notifyUserEvent(
            UUID eventId, String eventType, User user, NotificationType type,
            String title, String message, String link, boolean emailAdvisory
    ) {
        return notifyUserEvent(eventId, eventType, user, type, title, message, link, emailAdvisory, Map.of());
    }

    public DeliveryStatus notifyUserEvent(
            UUID eventId, String eventType, User user, NotificationType type,
            String title, String message, String link, boolean emailAdvisory,
            Map<String, Object> eligibilityContext
    ) {
        String safeTitle = limit(title, 160);
        String safeMessage = limit(message, 1000);
        String validatedLink = safeLink(link);
        if (!persistNotification(eventId, eventType, user, type, safeTitle, safeMessage, validatedLink)) {
            return DeliveryStatus.QUEUED;
        }
        if (!emailAdvisory) return DeliveryStatus.NOT_REQUIRED;
        Map<String, Object> payload = new java.util.HashMap<>(eligibilityContext);
        payload.put("fullName", limit(user.getFullName(), 160));
        payload.put("title", safeTitle);
        payload.put("message", safeMessage);
        payload.put("link", validatedLink == null ? "" : validatedLink);
        payload.put("notificationType", type.name());
        OutboxEnqueueRequest delivery = new OutboxEnqueueRequest(
                eventId, eventType, user.getId(), user.getEmail(), "IN_APP_NOTIFICATION",
                payload,
                false, null, 0, correlationId()
        );
        if (!userProfileService.shouldEmailForNotification(user, type, eventType)) {
            return outboxService.recordSuppressed(delivery);
        }
        return outboxService.enqueue(delivery);
    }

    private String correlationId() {
        RequestMetadata metadata = RequestMetadata.current();
        return metadata == null ? null : metadata.correlationId();
    }

    private boolean persistNotification(
            UUID eventId, String eventType, User user,
            NotificationType type,
            String title,
            String message,
            String link
    ) {
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            return false;
        }
        if (notificationRepository.existsByEventIdAndUser_Id(eventId, user.getId())) {
            return false;
        }
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(limit(title, 160));
        notification.setMessage(limit(message, 1000));
        notification.setLink(safeLink(link));
        notification.setEventId(eventId);
        notification.setEventType(eventType);
        notificationRepository.save(notification);
        return true;
    }

    private String safeLink(String link) {
        if (link == null) return null;
        String value = limit(link.trim(), 500);
        return value.startsWith("/") && !value.startsWith("//") ? value : null;
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, Math.min(maximum, value.length()));
    }

    public void notifyUserId(Long userId, NotificationType type, String title, String message, String link) {
        userRepository.findById(userId).ifPresent(user -> notifyUser(user, type, title, message, link));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(int page, int size) {
        User currentUser = securityUtils.getCurrentUser();
        var pageable = Pagination.page(page, size, "createdAt", "desc", java.util.Set.of("createdAt"));
        return PageResponse.from(notificationRepository.findByUserId(currentUser.getId(), pageable), this::map);
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
