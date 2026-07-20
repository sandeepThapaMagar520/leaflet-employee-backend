package com.ems.backend.media;

import com.ems.backend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "media_assets")
public class MediaAsset {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status;

    @Column(nullable = false)
    private String provider;
    private String providerAssetId;
    private String providerPublicId;
    @Column(nullable = false)
    private String resourceType;
    @Column(nullable = false)
    private String deliveryType;
    private String providerSecureUrl;
    private String originalFilename;
    @Column(nullable = false)
    private String detectedMimeType;
    @Column(nullable = false)
    private String detectedFormat;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private String checksumSha256;
    private Integer width;
    private Integer height;
    private Integer frameCount;
    @Column(nullable = false)
    private boolean privateAsset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanningStatus scanningStatus;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant verifiedAt;
    private Instant rejectedAt;
    private Instant attachedAt;
    private Instant deletedAt;
    @Version
    private long version;
    private String failureReasonCode;
    private String attachedResourceType;
    private String attachedResourceId;

    @PrePersist
    void create() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
