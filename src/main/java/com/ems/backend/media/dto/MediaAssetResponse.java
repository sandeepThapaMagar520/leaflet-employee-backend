package com.ems.backend.media.dto;

import com.ems.backend.media.MediaStatus;
import com.ems.backend.media.ScanningStatus;
import com.ems.backend.media.UploadPurpose;

import java.time.Instant;
import java.util.UUID;

public record MediaAssetResponse(
        UUID id,
        UploadPurpose purpose,
        MediaStatus status,
        ScanningStatus scanningStatus,
        String fileName,
        String detectedMimeType,
        String detectedFormat,
        long sizeBytes,
        Integer width,
        Integer height,
        String deliveryUrl,
        Instant createdAt
) {}
