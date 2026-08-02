package com.ems.backend.settings;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppSettingsServiceTest {
    @Test
    void cachesFrequentlyReadSettingsForTheConfiguredTtl() {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        AppSetting setting = new AppSetting();
        setting.setKey(AppSettingsService.ATTENDANCE_REQUIRED_MINUTES);
        setting.setValue("420");
        when(repository.findById(AppSettingsService.ATTENDANCE_REQUIRED_MINUTES))
                .thenReturn(Optional.of(setting));
        AppSettingsService service = new AppSettingsService(repository);

        assertEquals(420, service.attendanceRequiredMinutes());
        assertEquals(420, service.attendanceRequiredMinutes());

        verify(repository, times(1)).findById(AppSettingsService.ATTENDANCE_REQUIRED_MINUTES);
    }
}
