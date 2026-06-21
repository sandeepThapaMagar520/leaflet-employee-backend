package com.ems.backend.user;

import com.ems.backend.common.SecurityUtils;
import com.ems.backend.mail.EmailService;
import com.ems.backend.user.dto.UpdateProfileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserNotificationSettingsRepository notificationSettingsRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserProfileService userProfileService;

    @Test
    void updateMyProfileChangesPersonalFieldsButPreservesAdminManagedFields() {
        User user = new User();
        user.setFullName("Original Name");
        user.setEmployeeId("EMP-101");
        user.setJoiningDate(LocalDate.of(2025, 1, 15));
        user.setEmploymentType(EmploymentType.FULL_TIME);
        user.setDepartment("Engineering");
        user.setJobTitle("Developer");

        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);

        var response = userProfileService.updateMyProfile(new UpdateProfileRequest(
                "Updated Name",
                "+977 9800000000",
                "Family / +977 9811111111",
                "Pokhara",
                "Asia/Kathmandu",
                null
        ));

        assertEquals("Updated Name", response.fullName());
        assertEquals("+977 9800000000", response.phone());
        assertEquals("Family / +977 9811111111", response.emergencyContact());
        assertEquals("Pokhara", response.location());
        assertEquals("EMP-101", response.employeeId());
        assertEquals(LocalDate.of(2025, 1, 15), response.joiningDate());
        assertEquals(EmploymentType.FULL_TIME, response.employmentType());
        assertEquals("Engineering", response.department());
        assertEquals("Developer", response.jobTitle());
    }
}
