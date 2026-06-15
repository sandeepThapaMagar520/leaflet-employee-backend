package com.ems.backend.project.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreatePaymentRequest(
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        @NotNull Instant paidAt,
        String referenceNote,
        @Size(max = 5) List<@Valid PaymentAttachmentRequest> attachments
) {
}
