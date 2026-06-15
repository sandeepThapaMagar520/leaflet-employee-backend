package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentAttachmentRequest(
        @NotBlank
        @Size(max = 1000)
        @Pattern(regexp = "^https://.+", message = "Bill file URL must use HTTPS")
        String fileUrl,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 100) String fileType
) {
}
