package com.ems.backend.media;

public record DetectedMedia(
        String mimeType,
        String format,
        long sizeBytes,
        String checksumSha256,
        Integer width,
        Integer height,
        Integer frameCount,
        String safeFilename
) {}
