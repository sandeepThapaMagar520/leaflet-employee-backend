package com.ems.backend.project.dto;

import java.util.UUID;

public record PaymentAttachmentResponse(
        Long id,
        UUID mediaAssetId,
        String downloadUrl,
        String fileName,
        String fileType,
        String legacyAssetStatus
) {
}
