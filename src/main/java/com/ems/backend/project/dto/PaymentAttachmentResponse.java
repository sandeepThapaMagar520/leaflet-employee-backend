package com.ems.backend.project.dto;

public record PaymentAttachmentResponse(
        Long id,
        String fileUrl,
        String fileName,
        String fileType
) {
}
