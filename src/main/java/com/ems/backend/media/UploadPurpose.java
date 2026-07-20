package com.ems.backend.media;

import com.ems.backend.user.Role;

import java.util.Set;

public enum UploadPurpose {
    PROFILE_IMAGE(
            Set.of("jpeg", "png"), 5 * 1024 * 1024L,
            128, 4096, 16_000_000L, false,
            false, "image", "upload", "leaflet/profile", false, 10
    ),
    PROJECT_ATTACHMENT(
            Set.of("jpeg", "png", "pdf"), 10 * 1024 * 1024L,
            1, 8192, 25_000_000L, false,
            true, "auto", "authenticated", "leaflet/project", true, 20
    ),
    TASK_ATTACHMENT(
            Set.of("jpeg", "png", "pdf"), 10 * 1024 * 1024L,
            1, 8192, 25_000_000L, false,
            true, "auto", "authenticated", "leaflet/task", true, 20
    ),
    PAYMENT_ATTACHMENT(
            Set.of("jpeg", "png", "pdf"), 10 * 1024 * 1024L,
            1, 8192, 25_000_000L, false,
            true, "auto", "authenticated", "leaflet/payment", true, 20
    ),
    HR_DOCUMENT(
            Set.of("pdf"), 10 * 1024 * 1024L,
            0, 0, 0, false,
            true, "raw", "authenticated", "leaflet/hr", true, 10
    );

    private final Set<String> formats;
    private final long maximumBytes;
    private final int minimumDimension;
    private final int maximumDimension;
    private final long maximumPixels;
    private final boolean animationAllowed;
    private final boolean privateAsset;
    private final String configuredResourceType;
    private final String deliveryType;
    private final String folder;
    private final boolean malwareScanRequired;
    private final int hourlyAttempts;

    UploadPurpose(
            Set<String> formats,
            long maximumBytes,
            int minimumDimension,
            int maximumDimension,
            long maximumPixels,
            boolean animationAllowed,
            boolean privateAsset,
            String configuredResourceType,
            String deliveryType,
            String folder,
            boolean malwareScanRequired,
            int hourlyAttempts
    ) {
        this.formats = formats;
        this.maximumBytes = maximumBytes;
        this.minimumDimension = minimumDimension;
        this.maximumDimension = maximumDimension;
        this.maximumPixels = maximumPixels;
        this.animationAllowed = animationAllowed;
        this.privateAsset = privateAsset;
        this.configuredResourceType = configuredResourceType;
        this.deliveryType = deliveryType;
        this.folder = folder;
        this.malwareScanRequired = malwareScanRequired;
        this.hourlyAttempts = hourlyAttempts;
    }

    public Set<String> formats() { return formats; }
    public long maximumBytes() { return maximumBytes; }
    public int minimumDimension() { return minimumDimension; }
    public int maximumDimension() { return maximumDimension; }
    public long maximumPixels() { return maximumPixels; }
    public boolean animationAllowed() { return animationAllowed; }
    public boolean privateAsset() { return privateAsset; }
    public String deliveryType() { return deliveryType; }
    public String folder() { return folder; }
    public boolean malwareScanRequired() { return malwareScanRequired; }
    public int hourlyAttempts() { return hourlyAttempts; }

    public boolean canUpload(Role role) {
        return switch (this) {
            case HR_DOCUMENT -> role == Role.ADMIN;
            case PAYMENT_ATTACHMENT -> role == Role.ADMIN || role == Role.MANAGER;
            case PROFILE_IMAGE, PROJECT_ATTACHMENT, TASK_ATTACHMENT -> role != null;
        };
    }

    public Set<String> attachmentTargets() {
        return switch (this) {
            case PROFILE_IMAGE -> Set.of("USER_PROFILE");
            case PROJECT_ATTACHMENT -> Set.of("PROJECT", "PROJECT_NOTE");
            case TASK_ATTACHMENT -> Set.of("TASK_COMMENT");
            case PAYMENT_ATTACHMENT -> Set.of("PROJECT_PAYMENT");
            case HR_DOCUMENT -> Set.of("STAFF_DOCUMENT");
        };
    }

    public boolean replacementAllowed() {
        return this == PROFILE_IMAGE
                || this == PROJECT_ATTACHMENT
                || this == PAYMENT_ATTACHMENT;
    }

    public boolean deletionAllowed() {
        return true;
    }

    public int unattachedRetentionHours() {
        return this == HR_DOCUMENT ? 4 : 24;
    }

    public String resourceType(String detectedFormat) {
        if (!"auto".equals(configuredResourceType)) {
            return configuredResourceType;
        }
        return "pdf".equals(detectedFormat) ? "raw" : "image";
    }
}
