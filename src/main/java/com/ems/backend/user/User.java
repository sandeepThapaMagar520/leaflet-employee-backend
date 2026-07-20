package com.ems.backend.user;

import com.ems.backend.media.MediaAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "profile_photo_url")
    private String profilePhotoUrl;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_media_asset_id")
    private MediaAsset profileMediaAsset;

    @Column(name = "profile_photo_legacy_status", nullable = false)
    private String profilePhotoLegacyStatus = "NONE";

    private String phone;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType = EmploymentType.FULL_TIME;

    private String department;

    private String location;

    @Column(name = "emergency_contact")
    private String emergencyContact;

    @Column(nullable = false)
    private String timezone = "Asia/Kathmandu";

    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = false;

    @Column(name = "email_verification_token_hash")
    private String emailVerificationTokenHash;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "leave_balance_adjustment_days", nullable = false)
    private Integer leaveBalanceAdjustmentDays = 0;

    @Column(name = "sick_leave_balance_adjustment_days", nullable = false)
    private Integer sickLeaveBalanceAdjustmentDays = 0;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "security_version", nullable = false)
    private Integer securityVersion = 1;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword = false;

    @Column(name = "password_otp_hash")
    private String passwordOtpHash;

    @Column(name = "password_otp_expires_at")
    private Instant passwordOtpExpiresAt;

    @Column(name = "password_otp_failed_attempts", nullable = false)
    private Integer passwordOtpFailedAttempts = 0;

    @Column(name = "password_otp_issued_at")
    private Instant passwordOtpIssuedAt;

    @Column(name = "password_otp_purpose")
    private String passwordOtpPurpose;

    @Column(name = "password_reset_token_hash")
    private String passwordResetTokenHash;

    @Column(name = "password_reset_expires_at")
    private Instant passwordResetExpiresAt;

    @Column(name = "pending_email")
    private String pendingEmail;

    @Column(name = "email_change_otp_hash")
    private String emailChangeOtpHash;

    @Column(name = "email_change_otp_expires_at")
    private Instant emailChangeOtpExpiresAt;

    @Column(name = "email_change_otp_failed_attempts", nullable = false)
    private Integer emailChangeOtpFailedAttempts = 0;

    @Column(name = "email_change_otp_issued_at")
    private Instant emailChangeOtpIssuedAt;
}
