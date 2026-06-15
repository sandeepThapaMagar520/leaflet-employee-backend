package com.ems.backend.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdatePaymentAttachmentsRequest(
        @NotNull @Size(max = 5) List<@Valid PaymentAttachmentRequest> attachments
) {
}
