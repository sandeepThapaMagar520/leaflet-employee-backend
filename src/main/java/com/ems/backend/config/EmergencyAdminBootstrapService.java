package com.ems.backend.config;

import com.ems.backend.settings.AppSetting;
import com.ems.backend.settings.AppSettingRepository;
import com.ems.backend.user.EmploymentType;
import com.ems.backend.user.Role;
import com.ems.backend.user.StaffAuditAction;
import com.ems.backend.user.StaffAuditEvent;
import com.ems.backend.user.StaffAuditEventRepository;
import com.ems.backend.user.User;
import com.ems.backend.user.UserNotificationSettings;
import com.ems.backend.user.UserNotificationSettingsRepository;
import com.ems.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EmergencyAdminBootstrapService {
    static final String COMPLETED_SETTING_KEY = "security.emergency-admin-bootstrap.completed";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> KNOWN_WEAK_PASSWORDS = Set.of(
            "password",
            "password123",
            "admin",
            "admin123",
            "changeme",
            "letmein",
            "qwerty123",
            "welcome123"
    );

    private final EmergencyAdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final UserNotificationSettingsRepository notificationSettingsRepository;
    private final StaffAuditEventRepository auditEventRepository;
    private final AppSettingRepository appSettingRepository;
    private final PasswordEncoder passwordEncoder;

    public EmergencyAdminBootstrapService(
            EmergencyAdminBootstrapProperties properties,
            UserRepository userRepository,
            UserNotificationSettingsRepository notificationSettingsRepository,
            StaffAuditEventRepository auditEventRepository,
            AppSettingRepository appSettingRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.auditEventRepository = auditEventRepository;
        this.appSettingRepository = appSettingRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public BootstrapResult bootstrapIfEnabled() {
        if (!properties.enabled()) {
            return BootstrapResult.DISABLED;
        }
        if (isCompleted()) {
            return BootstrapResult.ALREADY_COMPLETED;
        }

        String email = normalizeEmail(properties.email());
        String fullName = normalizeFullName(properties.fullName());
        validatePassword(properties.password());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException(
                    "Emergency administrator bootstrap refused because the configured email already exists"
            );
        }

        User administrator = new User();
        administrator.setFullName(fullName);
        administrator.setEmail(email);
        administrator.setPassword(passwordEncoder.encode(properties.password()));
        administrator.setRole(Role.ADMIN);
        administrator.setActive(true);
        administrator.setJobTitle("Emergency Administrator");
        administrator.setEmploymentType(EmploymentType.FULL_TIME);
        administrator.setEmailVerified(false);
        administrator.setMustChangePassword(true);
        administrator.setPasswordChangedAt(Instant.now());
        User saved = userRepository.save(administrator);

        UserNotificationSettings settings = new UserNotificationSettings();
        settings.setUser(saved);
        notificationSettingsRepository.save(settings);

        StaffAuditEvent auditEvent = new StaffAuditEvent();
        auditEvent.setStaffUser(saved);
        auditEvent.setActor(null);
        auditEvent.setAction(StaffAuditAction.EMERGENCY_ADMIN_BOOTSTRAPPED);
        auditEvent.setDescription(
                "Emergency administrator created by the explicitly enabled one-time bootstrap process."
        );
        auditEventRepository.save(auditEvent);

        AppSetting completed = new AppSetting();
        completed.setKey(COMPLETED_SETTING_KEY);
        completed.setValue("true");
        appSettingRepository.save(completed);
        return BootstrapResult.CREATED;
    }

    private boolean isCompleted() {
        return appSettingRepository.findById(COMPLETED_SETTING_KEY)
                .map(AppSetting::getValue)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    private String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalStateException("Emergency administrator bootstrap requires a valid email address");
        }
        return email;
    }

    private String normalizeFullName(String value) {
        String fullName = value == null ? "" : value.trim();
        if (fullName.length() < 2 || fullName.length() > 255) {
            throw new IllegalStateException(
                    "Emergency administrator bootstrap requires a full name between 2 and 255 characters"
            );
        }
        return fullName;
    }

    static void validatePassword(String value) {
        String password = value == null ? "" : value;
        String normalized = password.trim().toLowerCase(Locale.ROOT);
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(character -> !Character.isLetterOrDigit(character));

        if (password.length() < 16
                || password.length() > 128
                || !hasUpper
                || !hasLower
                || !hasDigit
                || !hasSpecial
                || KNOWN_WEAK_PASSWORDS.contains(normalized)
                || normalized.contains("password")
                || normalized.contains("changeme")) {
            throw new IllegalStateException(
                    "Emergency administrator bootstrap password must be 16-128 characters and include upper, lower, digit, and special characters"
            );
        }
    }

    public enum BootstrapResult {
        DISABLED,
        ALREADY_COMPLETED,
        CREATED
    }
}
