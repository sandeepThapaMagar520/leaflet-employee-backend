package com.ems.backend.user;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.mail.EmailService;
import com.ems.backend.auth.EmailVerificationTokenService;
import com.ems.backend.media.MediaAsset;
import com.ems.backend.media.MediaAttachmentService;
import com.ems.backend.media.UploadPurpose;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.user.dto.NotificationPreferencesResponse;
import com.ems.backend.user.dto.ProfileResponse;
import com.ems.backend.user.dto.UpdateNotificationPreferencesRequest;
import com.ems.backend.user.dto.UpdateProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UserProfileService {
    private final UserRepository userRepository;
    private final UserNotificationSettingsRepository notificationSettingsRepository;
    private final SecurityUtils securityUtils;
    private final EmailService emailService;
    private final EmailVerificationTokenService emailVerificationTokenService;
    private final MediaAttachmentService mediaAttachmentService;

    public UserProfileService(
            UserRepository userRepository,
            UserNotificationSettingsRepository notificationSettingsRepository,
            SecurityUtils securityUtils,
            EmailService emailService,
            EmailVerificationTokenService emailVerificationTokenService,
            MediaAttachmentService mediaAttachmentService
    ) {
        this.userRepository = userRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.securityUtils = securityUtils;
        this.emailService = emailService;
        this.emailVerificationTokenService = emailVerificationTokenService;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile() {
        return mapProfile(securityUtils.getCurrentUser());
    }

    @Transactional
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
        if (request.profileMediaAssetId() != null
                && (user.getProfileMediaAsset() == null
                || !request.profileMediaAssetId().equals(user.getProfileMediaAsset().getId()))) {
            MediaAsset previous = user.getProfileMediaAsset();
            MediaAsset profile = mediaAttachmentService.attach(
                    request.profileMediaAssetId(),
                    UploadPurpose.PROFILE_IMAGE,
                    user,
                    user,
                    "USER_PROFILE",
                    user.getId().toString()
            );
            user.setProfileMediaAsset(profile);
            user.setProfilePhotoUrl(profile.getProviderSecureUrl());
            user.setProfilePhotoLegacyStatus("NONE");
            User saved = userRepository.saveAndFlush(user);
            if (previous != null) {
                mediaAttachmentService.deleteAttached(previous, user, "PROFILE_REPLACED");
            }
            return mapProfile(saved);
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
        EmailVerificationTokenService.IssuedVerification issued =
                emailVerificationTokenService.issue(user.getId());
        if (issued == null || !emailService.sendVerificationEmail(
                issued.email(), issued.fullName(), issued.rawToken()
        )) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification state was created, but the email could not be delivered."
            );
        }
    }

    public void resendVerificationEmail() {
        User user = securityUtils.getCurrentUser();
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ResponseStatusException(BAD_REQUEST, "Email is already verified.");
        }
        issueVerificationEmail(user);
    }

    public void verifyEmail(String token, RequestMetadata metadata) {
        EmailVerificationTokenService.VerificationResult result =
                emailVerificationTokenService.consume(token, metadata);
        if (result != EmailVerificationTokenService.VerificationResult.SUCCESS) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid or expired verification link.");
        }
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
                user.getProfileMediaAsset() == null ? null : user.getProfilePhotoUrl(),
                user.getProfileMediaAsset() == null ? null : user.getProfileMediaAsset().getId(),
                user.getProfilePhotoLegacyStatus(),
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
