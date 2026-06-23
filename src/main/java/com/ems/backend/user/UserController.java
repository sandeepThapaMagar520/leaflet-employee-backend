package com.ems.backend.user;

import com.ems.backend.common.PageResponse;
import com.ems.backend.user.dto.UserResponse;
import com.ems.backend.user.dto.StaffOverviewResponse;
import com.ems.backend.user.dto.CreateStaffDocumentRequest;
import com.ems.backend.user.dto.StaffDocumentResponse;
import com.ems.backend.user.dto.StaffDirectorySummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final StaffOverviewService staffOverviewService;

    public UserController(
            UserService userService,
            UserProfileService userProfileService,
            StaffOverviewService staffOverviewService
    ) {
        this.userService = userService;
        this.userProfileService = userProfileService;
        this.staffOverviewService = staffOverviewService;
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

    @GetMapping("/me/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "My staff documents", description = "Returns HR documents uploaded to the authenticated user's staff record.")
    public List<StaffDocumentResponse> getMyDocuments() {
        return userService.getMyDocuments();
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
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) AccountStatus accountStatus,
            @RequestParam(required = false) EmploymentType employmentType,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "false") boolean incompleteOnly
    ) {
        if (page != null || size != null) {
            return userService.getUsersPaged(
                    page != null ? page : 0,
                    size != null ? size : 20,
                    search,
                    role,
                    active,
                    accountStatus,
                    employmentType,
                    department,
                    incompleteOnly
            );
        }
        return userService.getAllUsers();
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Staff directory summary", description = "Company-wide staff counts and available department filters.")
    public StaffDirectorySummaryResponse getStaffDirectorySummary() {
        return userService.getStaffDirectorySummary();
    }

    @GetMapping("/{id}/overview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Staff overview", description = "Admin-only operational record for one staff member.")
    public StaffOverviewResponse getStaffOverview(@PathVariable Long id) {
        return staffOverviewService.getOverview(id);
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add staff document", description = "Stores staff document metadata after upload.")
    public StaffDocumentResponse addStaffDocument(
            @PathVariable Long id,
            @Valid @RequestBody CreateStaffDocumentRequest request
    ) {
        return userService.addDocument(id, request);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete staff document", description = "Removes staff document metadata from a staff record.")
    public void deleteStaffDocument(@PathVariable Long id, @PathVariable Long documentId) {
        userService.deleteDocument(id, documentId);
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
