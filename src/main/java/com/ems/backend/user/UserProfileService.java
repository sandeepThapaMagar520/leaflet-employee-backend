package com.ems.backend.user;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.mail.EmailService;
import com.ems.backend.user.dto.NotificationPreferencesResponse;
import com.ems.backend.user.dto.ProfileResponse;
import com.ems.backend.user.dto.UpdateNotificationPreferencesRequest;
import com.ems.backend.user.dto.UpdateProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class UserProfileService {
    private final UserRepository userRepository;
    private final UserNotificationSettingsRepository notificationSettingsRepository;
    private final SecurityUtils securityUtils;
    private final EmailService emailService;

    public UserProfileService(
            UserRepository userRepository,
            UserNotificationSettingsRepository notificationSettingsRepository,
            SecurityUtils securityUtils,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.securityUtils = securityUtils;
        this.emailService = emailService;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile() {
        return mapProfile(securityUtils.getCurrentUser());
    }

    public ProfileResponse updateMyProfile(UpdateProfileRequest request) {
        User user = securityUtils.getCurrentUser();

        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        }
        if (request.emergencyContact() != null) {
            user.setEmergencyContact(request.emergencyContact().isBlank() ? null : request.emergencyContact().trim());
        }
        if (request.location() != null) {
            user.setLocation(request.location().isBlank() ? null : request.location().trim());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            user.setTimezone(request.timezone().trim());
        }
        if (request.profilePhotoUrl() != null) {
            user.setProfilePhotoUrl(request.profilePhotoUrl().isBlank() ? null : request.profilePhotoUrl().trim());
        }

        return mapProfile(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getNotificationPreferences() {
        return mapPreferences(getOrCreateSettings(securityUtils.getCurrentUser()));
    }

    public NotificationPreferencesResponse updateNotificationPreferences(UpdateNotificationPreferencesRequest request) {
        UserNotificationSettings settings = getOrCreateSettings(securityUtils.getCurrentUser());

        if (request.emailTaskAssigned() != null) settings.setEmailTaskAssigned(request.emailTaskAssigned());
        if (request.emailTaskCompleted() != null) settings.setEmailTaskCompleted(request.emailTaskCompleted());
        if (request.emailTaskCommented() != null) settings.setEmailTaskCommented(request.emailTaskCommented());
        if (request.emailTaskDueSoon() != null) settings.setEmailTaskDueSoon(request.emailTaskDueSoon());
        if (request.emailTaskOverdue() != null) settings.setEmailTaskOverdue(request.emailTaskOverdue());
        if (request.emailProjectAssigned() != null) settings.setEmailProjectAssigned(request.emailProjectAssigned());
        if (request.emailLeaveUpdates() != null) settings.setEmailLeaveUpdates(request.emailLeaveUpdates());

        return mapPreferences(notificationSettingsRepository.save(settings));
    }

    public void issueVerificationEmail(User user) {
        String token = UUID.randomUUID().toString();
        user.setEmailVerificationToken(token);
        user.setEmailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        user.setEmailVerified(false);
        userRepository.save(user);
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), token);
    }

    public void resendVerificationEmail() {
        User user = securityUtils.getCurrentUser();
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ResponseStatusException(BAD_REQUEST, "Email is already verified.");
        }
        issueVerificationEmail(user);
    }

    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Invalid or expired verification link."));

        if (user.getEmailVerificationExpiresAt() == null
                || user.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(BAD_REQUEST, "Verification link has expired. Please request a new one.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiresAt(null);
        userRepository.save(user);
    }

    public UserNotificationSettings getOrCreateSettings(User user) {
        return notificationSettingsRepository.findById(user.getId())
                .orElseGet(() -> {
                    UserNotificationSettings settings = new UserNotificationSettings();
                    settings.setUser(user);
                    return notificationSettingsRepository.save(settings);
                });
    }

    public boolean shouldEmailForNotification(User user, com.ems.backend.notification.NotificationType type) {
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            return false;
        }
        UserNotificationSettings settings = getOrCreateSettings(user);
        return switch (type) {
            case TASK_ASSIGNED -> Boolean.TRUE.equals(settings.getEmailTaskAssigned());
            case TASK_COMPLETED -> Boolean.TRUE.equals(settings.getEmailTaskCompleted());
            case TASK_COMMENTED -> Boolean.TRUE.equals(settings.getEmailTaskCommented());
            case TASK_DUE_SOON -> Boolean.TRUE.equals(settings.getEmailTaskDueSoon());
            case TASK_OVERDUE -> Boolean.TRUE.equals(settings.getEmailTaskOverdue());
            case PROJECT_ASSIGNED -> Boolean.TRUE.equals(settings.getEmailProjectAssigned());
            case SYSTEM -> Boolean.TRUE.equals(settings.getEmailLeaveUpdates());
        };
    }

    public static ProfileResponse mapProfile(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getProfilePhotoUrl(),
                user.getEmployeeId(),
                user.getJoiningDate(),
                user.getEmploymentType(),
                user.getPhone(),
                user.getEmergencyContact(),
                user.getJobTitle(),
                user.getDepartment(),
                user.getLocation(),
                user.getTimezone(),
                user.getEmailVerified(),
                user.getMustChangePassword(),
                user.getLastLoginAt(),
                user.getPasswordChangedAt()
        );
    }

    private NotificationPreferencesResponse mapPreferences(UserNotificationSettings settings) {
        return new NotificationPreferencesResponse(
                Boolean.TRUE.equals(settings.getEmailTaskAssigned()),
                Boolean.TRUE.equals(settings.getEmailTaskCompleted()),
                Boolean.TRUE.equals(settings.getEmailTaskCommented()),
                Boolean.TRUE.equals(settings.getEmailTaskDueSoon()),
                Boolean.TRUE.equals(settings.getEmailTaskOverdue()),
                Boolean.TRUE.equals(settings.getEmailProjectAssigned()),
                Boolean.TRUE.equals(settings.getEmailLeaveUpdates())
        );
    }
}
