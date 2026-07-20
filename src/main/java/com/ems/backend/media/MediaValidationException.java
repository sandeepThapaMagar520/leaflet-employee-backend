package com.ems.backend.media;

public class MediaValidationException extends RuntimeException {
    private final String reasonCode;

    public MediaValidationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
