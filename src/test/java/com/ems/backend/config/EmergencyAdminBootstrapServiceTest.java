package com.ems.backend.config;

import com.ems.backend.settings.AppSetting;
import com.ems.backend.settings.AppSettingRepository;
import com.ems.backend.user.StaffAuditAction;
import com.ems.backend.user.StaffAuditEventRepository;
import com.ems.backend.user.User;
import com.ems.backend.user.UserNotificationSettingsRepository;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmergencyAdminBootstrapServiceTest {
    private static final String STRONG_ONE_TIME_PASSWORD = "N0t-A-Real-Passphrase!2026";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserNotificationSettingsRepository notificationSettingsRepository;
    @Mock
    private StaffAuditEventRepository auditEventRepository;
    @Mock
    private AppSettingRepository appSettingRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void bootstrapCannotRunWithoutExplicitFeatureFlag() {
        EmergencyAdminBootstrapService service = service(false, STRONG_ONE_TIME_PASSWORD);

        assertEquals(
                EmergencyAdminBootstrapService.BootstrapResult.DISABLED,
                service.bootstrapIfEnabled()
        );
        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrapRejectsKnownWeakPassword() {
        EmergencyAdminBootstrapService service = service(true, "password");
        when(appSettingRepository.findById(EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, service::bootstrapIfEnabled);
        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrapRejectsAnotherKnownWeakPassword() {
        EmergencyAdminBootstrapService service = service(true, "ChangeMe123!");
        when(appSettingRepository.findById(EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, service::bootstrapIfEnabled);
        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrapCreatesForcedSetupAdminAndAuditEvent() {
        when(passwordEncoder.encode(any())).thenReturn("encoded-bootstrap-password");
        EmergencyAdminBootstrapService service = service(true, STRONG_ONE_TIME_PASSWORD);
        when(appSettingRepository.findById(EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("recovery-admin@example.net")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(100L);
            return user;
        });

        assertEquals(
                EmergencyAdminBootstrapService.BootstrapResult.CREATED,
                service.bootstrapIfEnabled()
        );
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(user ->
                user.getRole().name().equals("ADMIN")
                        && Boolean.TRUE.equals(user.getActive())
                        && Boolean.TRUE.equals(user.getMustChangePassword())
                        && Boolean.FALSE.equals(user.getEmailVerified())
                        && "encoded-bootstrap-password".equals(user.getPassword())
        ));
        verify(auditEventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getAction() == StaffAuditAction.EMERGENCY_ADMIN_BOOTSTRAPPED
                        && event.getActor() == null
        ));
        verify(appSettingRepository).save(org.mockito.ArgumentMatchers.argThat(setting ->
                EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY.equals(setting.getKey())
                        && "true".equals(setting.getValue())
        ));
    }

    @Test
    void bootstrapDoesNotRunTwice() {
        when(passwordEncoder.encode(any())).thenReturn("encoded-bootstrap-password");
        AtomicBoolean completed = new AtomicBoolean(false);
        EmergencyAdminBootstrapService service = service(true, STRONG_ONE_TIME_PASSWORD);
        when(appSettingRepository.findById(EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY))
                .thenAnswer(invocation -> completed.get()
                        ? Optional.of(completedSetting())
                        : Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("recovery-admin@example.net")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(101L);
            return user;
        });
        when(appSettingRepository.save(any(AppSetting.class))).thenAnswer(invocation -> {
            AppSetting setting = invocation.getArgument(0);
            if (EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY.equals(setting.getKey())) {
                completed.set(true);
            }
            return setting;
        });

        assertEquals(
                EmergencyAdminBootstrapService.BootstrapResult.CREATED,
                service.bootstrapIfEnabled()
        );
        assertEquals(
                EmergencyAdminBootstrapService.BootstrapResult.ALREADY_COMPLETED,
                service.bootstrapIfEnabled()
        );
        verify(userRepository, org.mockito.Mockito.times(1)).save(any(User.class));
    }

    private EmergencyAdminBootstrapService service(boolean enabled, String password) {
        return new EmergencyAdminBootstrapService(
                new EmergencyAdminBootstrapProperties(
                        enabled,
                        " Recovery-Admin@Example.net ",
                        "Emergency Recovery Administrator",
                        password
                ),
                userRepository,
                notificationSettingsRepository,
                auditEventRepository,
                appSettingRepository,
                passwordEncoder
        );
    }

    private AppSetting completedSetting() {
        AppSetting setting = new AppSetting();
        setting.setKey(EmergencyAdminBootstrapService.COMPLETED_SETTING_KEY);
        setting.setValue("true");
        return setting;
    }
}
