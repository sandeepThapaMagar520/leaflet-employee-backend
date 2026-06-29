package com.ems.backend.settings;

import com.ems.backend.settings.dto.AppSettingsResponse;
import com.ems.backend.settings.dto.UpdateAppSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Settings", description = "Admin-controlled company policy settings")
public class AppSettingsController {
    private final AppSettingsService service;

    public AppSettingsController(AppSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','EMPLOYEE')")
    @Operation(summary = "Get app settings")
    public AppSettingsResponse getSettings() {
        return service.getSettings();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update app settings")
    public AppSettingsResponse updateSettings(@Valid @RequestBody UpdateAppSettingsRequest request) {
        return service.updateSettings(request);
    }
}
