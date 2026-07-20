package com.ems.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public record RequestMetadata(String clientIp, String userAgent, String correlationId) {
    public static final String CORRELATION_ATTRIBUTE = RequestMetadata.class.getName() + ".correlationId";

    public static RequestMetadata from(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String clientIp = request.getRemoteAddr();
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] chain = forwardedFor.split(",");
            clientIp = chain[chain.length - 1].trim();
        }
        return new RequestMetadata(
                limit(clientIp, 64),
                limit(request.getHeader("User-Agent"), 500),
                limit((String) request.getAttribute(CORRELATION_ATTRIBUTE), 80)
        );
    }

    public static RequestMetadata current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return from(attributes.getRequest());
        }
        return null;
    }

    private static String limit(String value, int length) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), length));
    }
}
