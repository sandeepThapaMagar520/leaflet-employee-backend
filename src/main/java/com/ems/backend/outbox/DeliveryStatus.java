package com.ems.backend.outbox;

public enum DeliveryStatus {
    QUEUED,
    SENT,
    FAILED,
    NOT_REQUIRED,
    SUPPRESSED
}
