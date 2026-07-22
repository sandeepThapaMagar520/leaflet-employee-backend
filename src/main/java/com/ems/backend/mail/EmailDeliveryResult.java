package com.ems.backend.mail;

public record EmailDeliveryResult(
        Outcome outcome,
        String providerMessageId,
        String reasonCode,
        String safeSummary
) {
    public enum Outcome { ACCEPTED, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    public static EmailDeliveryResult accepted(String providerMessageId) {
        return new EmailDeliveryResult(Outcome.ACCEPTED, providerMessageId, null, null);
    }

    public static EmailDeliveryResult retryable(String code, String summary) {
        return new EmailDeliveryResult(Outcome.RETRYABLE_FAILURE, null, code, summary);
    }

    public static EmailDeliveryResult permanent(String code, String summary) {
        return new EmailDeliveryResult(Outcome.PERMANENT_FAILURE, null, code, summary);
    }
}
