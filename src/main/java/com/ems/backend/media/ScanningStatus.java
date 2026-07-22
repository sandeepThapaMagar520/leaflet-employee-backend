package com.ems.backend.media;

public enum ScanningStatus {
    NOT_REQUIRED,
    PENDING,
    CLEAN,
    STRUCTURE_VALIDATED,
    MALWARE_DETECTED,
    FAILED,
    UNAVAILABLE
}
