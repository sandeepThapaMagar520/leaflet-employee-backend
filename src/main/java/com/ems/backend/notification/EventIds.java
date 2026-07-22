package com.ems.backend.notification;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class EventIds {
    private EventIds() {}

    public static UUID stable(String eventType, Object... identityParts) {
        StringBuilder identity = new StringBuilder(eventType);
        for (Object part : identityParts) identity.append(':').append(part);
        return UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8));
    }
}
