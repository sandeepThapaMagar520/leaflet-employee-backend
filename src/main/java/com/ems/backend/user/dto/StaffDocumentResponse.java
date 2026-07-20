package com.ems.backend.user.dto;

import com.ems.backend.user.StaffDocumentType;

import java.time.Instant;
import java.util.UUID;

public record StaffDocumentResponse(
        Long id,
        StaffDocumentType documentType,
        String fileName,
        UUID mediaAssetId,
        String downloadUrl,
        String legacyAssetStatus,
        String note,
        Instant createdAt
) {
}
