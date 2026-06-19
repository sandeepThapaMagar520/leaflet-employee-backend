package com.ems.backend.user.dto;

import com.ems.backend.user.StaffDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStaffDocumentRequest(
        @NotNull StaffDocumentType documentType,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 1000) String fileUrl,
        @Size(max = 1000) String note
) {
}
