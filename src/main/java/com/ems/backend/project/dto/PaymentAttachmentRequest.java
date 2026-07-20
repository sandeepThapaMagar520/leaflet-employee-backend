package com.ems.backend.project.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentAttachmentRequest(
        @NotNull UUID mediaAssetId
) {
}
