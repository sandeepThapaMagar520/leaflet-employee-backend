package com.ems.backend.auth;

import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.auth.dto.AuthResponse;
import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.OtpVerificationResponse;
import com.ems.backend.auth.dto.PasswordResetRequest;
import com.ems.backend.auth.dto.RegisterRequest;
import com.ems.backend.auth.dto.RequestEmailChange;
import com.ems.backend.auth.dto.SetPasswordRequest;
import com.ems.backend.auth.dto.StaffRegistrationResponse;
import com.ems.backend.auth.dto.VerifyEmailRequest;
import com.ems.backend.auth.dto.VerifyEmailChange;
import com.ems.backend.auth.dto.VerifyPasswordOtpRequest;
import com.ems.backend.user.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, staff registration, and password management")
public class AuthController {
    private final AuthService authService;
    private final UserProfileService userProfileService;

    public AuthController(AuthService authService, UserProfileService userProfileService) {
        this.authService = authService;
        this.userProfileService = userProfileService;
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a staff member", description = "Admin-only endpoint for creating employee, manager, or admin accounts.")
    public ResponseEntity<StaffRegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Returns a JWT access token and authenticated user details.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Change current password", description = "Authenticated users can update their password after confirming the current password.")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-email/request")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Request email change", description = "Sends an OTP to the requested new email address.")
    public ResponseEntity<Map<String, String>> requestEmailChange(@Valid @RequestBody RequestEmailChange request) {
        authService.requestEmailChange(request);
        return ResponseEntity.ok(Map.of("message", "A verification OTP was sent to the new email address."));
    }

    @PostMapping("/change-email/verify")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Verify email change", description = "Changes the account email after the new address OTP is verified.")
    public ResponseEntity<AuthResponse> verifyEmailChange(@Valid @RequestBody VerifyEmailChange request) {
        return ResponseEntity.ok(authService.verifyEmailChange(request));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password OTP", description = "Emails an OTP for first-time account setup or forgotten-password recovery.")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(Map.of(
                "message",
                "If an active account exists for that email, a password OTP has been sent."
        ));
    }

    @PostMapping("/verify-password-otp")
    @Operation(summary = "Verify password OTP", description = "Verifies the emailed OTP before allowing a new password.")
    public ResponseEntity<OtpVerificationResponse> verifyPasswordOtp(
            @Valid @RequestBody VerifyPasswordOtpRequest request
    ) {
        return ResponseEntity.ok(authService.verifyPasswordOtp(request));
    }

    @PostMapping("/set-password")
    @Operation(summary = "Set password", description = "Creates a new password using the short-lived token returned after OTP verification.")
    public ResponseEntity<Map<String, String>> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        authService.setPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password saved. You can now sign in."));
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Verify email", description = "Confirms a user's email address using the token from the verification email.")
    public ResponseEntity<Map<String, String>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        userProfileService.verifyEmail(request.token());
        return ResponseEntity.ok(Map.of("message", "Email verified successfully."));
    }

    @PostMapping("/resend-verification")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Resend verification email", description = "Sends a new email verification link to the authenticated user.")
    public ResponseEntity<Map<String, String>> resendVerification() {
        userProfileService.resendVerificationEmail();
        return ResponseEntity.ok(Map.of("message", "Verification email sent."));
    }
}
