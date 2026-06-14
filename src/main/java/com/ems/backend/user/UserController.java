package com.ems.backend.user;

import com.ems.backend.common.PageResponse;
import com.ems.backend.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import com.ems.backend.user.dto.NotificationPreferencesResponse;
import com.ems.backend.user.dto.ProfileResponse;
import com.ems.backend.user.dto.UpdateNotificationPreferencesRequest;
import com.ems.backend.user.dto.UpdateProfileRequest;
import com.ems.backend.user.dto.UpdateUserRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Staff directory and admin user management")
public class UserController {
    private final UserService userService;
    private final UserProfileService userProfileService;

    public UserController(UserService userService, UserProfileService userProfileService) {
        this.userService = userService;
        this.userProfileService = userProfileService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "My profile", description = "Returns the authenticated user's profile and account status.")
    public ProfileResponse getMyProfile() {
        return userProfileService.getMyProfile();
    }

    @PutMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Update my profile", description = "Self-service update for personal profile fields and photo URL.")
    public ProfileResponse updateMyProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return userProfileService.updateMyProfile(request);
    }

    @GetMapping("/me/notification-preferences")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Notification preferences", description = "Returns email notification preferences for the authenticated user.")
    public NotificationPreferencesResponse getNotificationPreferences() {
        return userProfileService.getNotificationPreferences();
    }

    @PutMapping("/me/notification-preferences")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Update notification preferences", description = "Updates email notification preferences for the authenticated user.")
    public NotificationPreferencesResponse updateNotificationPreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request
    ) {
        return userProfileService.updateNotificationPreferences(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "List users", description = "Admins and managers can list staff. Optional page, size, and search parameters return a filtered paged response.")
    public Object getAllUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String search
    ) {
        if (page != null || size != null) {
            return userService.getUsersPaged(page != null ? page : 0, size != null ? size : 20, search);
        }
        return userService.getAllUsers();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update user", description = "Admin-only endpoint for updating staff profile, role, and active status.")
    public UserResponse updateUser(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete user", description = "Admin-only endpoint for removing/deactivating a staff account.")
    public void deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
    }
}
