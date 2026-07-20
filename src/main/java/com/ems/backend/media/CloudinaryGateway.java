package com.ems.backend.media;

import java.nio.file.Path;
import java.time.Instant;

public interface CloudinaryGateway {
    ProviderAsset upload(
            Path file,
            UploadPurpose purpose,
            DetectedMedia detected,
            String expectedPublicId
    );

    void delete(String resourceType, String deliveryType, String publicId);

    String privateDownloadUrl(
            String resourceType,
            String deliveryType,
            String publicId,
            String format,
            Instant expiresAt
    );

    record ProviderAsset(
            String assetId,
            String publicId,
            String resourceType,
            String deliveryType,
            String secureUrl,
            String format,
            long bytes,
            Integer width,
            Integer height,
            Instant createdAt
    ) {}
}
