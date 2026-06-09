package com.ems.backend.project.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        BigDecimal amount,
        Instant paidAt,
        String referenceNote,
        String recordedByName
) {
}
