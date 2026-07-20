package com.ems.backend.user.dto;

import com.ems.backend.user.StaffDocumentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateStaffDocumentRequest(
        @NotNull StaffDocumentType documentType,
        @NotNull UUID mediaAssetId,
        @Size(max = 1000) String note
) {
}
