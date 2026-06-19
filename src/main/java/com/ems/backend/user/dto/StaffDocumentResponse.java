package com.ems.backend.user.dto;

import com.ems.backend.user.StaffDocumentType;

import java.time.Instant;

public record StaffDocumentResponse(
        Long id,
        StaffDocumentType documentType,
        String fileName,
        String fileUrl,
        String note,
        Instant createdAt
) {
}
