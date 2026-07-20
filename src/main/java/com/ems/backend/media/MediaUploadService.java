package com.ems.backend.media;

import com.ems.backend.auth.DatabaseRateLimitService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.config.MediaProperties;
import com.ems.backend.media.dto.MediaAssetResponse;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

@Service
public class MediaUploadService {
    private static final EnumSet<MediaStatus> PENDING =
            EnumSet.of(MediaStatus.PENDING, MediaStatus.QUARANTINED);
    private static final EnumSet<MediaStatus> STORED =
            EnumSet.of(MediaStatus.VERIFIED, MediaStatus.ATTACHED);
    private static final long ABSOLUTE_MAXIMUM = 10 * 1024 * 1024L;

    private final MediaAssetRepository repository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final MediaContentInspector inspector;
    private final MalwareScanner malwareScanner;
    private final CloudinaryGateway cloudinaryGateway;
    private final DatabaseRateLimitService rateLimitService;
    private final MediaProperties properties;
    private final SecurityAuditService auditService;

    public MediaUploadService(
            MediaAssetRepository repository,
            UserRepository userRepository,
            SecurityUtils securityUtils,
            MediaContentInspector inspector,
            MalwareScanner malwareScanner,
            CloudinaryGateway cloudinaryGateway,
            DatabaseRateLimitService rateLimitService,
            MediaProperties properties,
            SecurityAuditService auditService
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.inspector = inspector;
        this.malwareScanner = malwareScanner;
        this.cloudinaryGateway = cloudinaryGateway;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public MediaAssetResponse upload(UploadPurpose purpose, MultipartFile file) {
        User principal = securityUtils.getCurrentUser();
        authorizePurpose(principal, purpose);
        RequestMetadata metadata = RequestMetadata.current();
        enforceRateLimits(principal, purpose, metadata);
        User actor = userRepository.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user no longer exists."
                ));
        enforceQuotas(actor, purpose, file == null ? 0 : file.getSize());
        audit(
                actor, null, "UPLOAD_REQUESTED", "PENDING", purpose.name(),
                "purpose=%s,submittedBytes=%d".formatted(
                        purpose, file == null ? 0 : file.getSize()
                )
        );

        Path temporary = null;
        CloudinaryGateway.ProviderAsset uploaded = null;
        try {
            temporary = spool(file, purpose.maximumBytes());
            DetectedMedia detected = inspector.inspect(
                    temporary,
                    purpose,
                    file.getContentType(),
                    file.getOriginalFilename()
            );
            if (purpose.malwareScanRequired()) {
                MalwareScanner.ScanResult scan = malwareScanner.scan(temporary);
                if (scan != MalwareScanner.ScanResult.CLEAN) {
                    return quarantineOrReject(actor, purpose, detected, scan);
                }
            }

            UUID id = UUID.randomUUID();
            String publicId = purpose.folder() + "/" + id
                    + ("raw".equals(purpose.resourceType(detected.format()))
                    ? "." + detected.format() : "");
            uploaded = cloudinaryGateway.upload(temporary, purpose, detected, publicId);
            MediaAsset asset = baseAsset(id, actor, purpose, detected);
            applyProvider(asset, uploaded);
            asset.setStatus(MediaStatus.VERIFIED);
            asset.setScanningStatus(
                    purpose.malwareScanRequired()
                            ? ScanningStatus.CLEAN
                            : ScanningStatus.NOT_REQUIRED
            );
            asset.setVerifiedAt(Instant.now());
            MediaAsset saved = repository.saveAndFlush(asset);
            audit(
                    actor, saved, "UPLOAD_VERIFIED", "SUCCESS", "CONTENT_AND_PROVIDER_VERIFIED",
                    "format=%s,size=%d".formatted(detected.format(), detected.sizeBytes())
            );
            return map(saved);
        } catch (MediaValidationException exception) {
            audit(
                    actor, null, "UPLOAD_REJECTED", "DENIED", exception.reasonCode(),
                    "purpose=" + purpose.name()
            );
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (ResponseStatusException exception) {
            if (uploaded != null) {
                deleteProviderBestEffort(uploaded);
            }
            audit(
                    actor, null, "PROVIDER_RESPONSE_REJECTED", "FAILED",
                    "PROVIDER_UPLOAD_FAILED", "purpose=" + purpose.name()
            );
            throw exception;
        } catch (RuntimeException exception) {
            if (uploaded != null) {
                deleteProviderBestEffort(uploaded);
            }
            throw exception;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // The operating system temp cleaner is the final fallback.
                }
            }
        }
    }

    @Transactional
    public void deleteUnattached(UUID assetId) {
        User actor = securityUtils.getCurrentUser();
        MediaAsset asset = repository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Media asset not found."
                ));
        if (!asset.getOwner().getId().equals(actor.getId()) && actor.getRole() != Role.ADMIN) {
            audit(actor, asset, "CROSS_OWNER_MEDIA_ACCESS_DENIED", "DENIED", "DELETE", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this media asset.");
        }
        if (asset.getStatus() == MediaStatus.ATTACHED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Attached media must be removed through its parent record."
            );
        }
        if (asset.getStatus() == MediaStatus.DELETED) return;
        if (asset.getProviderPublicId() != null) {
            cloudinaryGateway.delete(
                    asset.getResourceType(),
                    asset.getDeliveryType(),
                    asset.getProviderPublicId()
            );
        }
        asset.setStatus(MediaStatus.DELETED);
        asset.setDeletedAt(Instant.now());
        repository.save(asset);
        audit(actor, asset, "MEDIA_DELETED", "SUCCESS", "OWNER_DELETE", null);
    }

    private MediaAssetResponse quarantineOrReject(
            User actor,
            UploadPurpose purpose,
            DetectedMedia detected,
            MalwareScanner.ScanResult scan
    ) {
        UUID id = UUID.randomUUID();
        MediaAsset asset = baseAsset(id, actor, purpose, detected);
        if (scan == MalwareScanner.ScanResult.MALWARE) {
            asset.setStatus(MediaStatus.REJECTED);
            asset.setScanningStatus(ScanningStatus.MALWARE_DETECTED);
            asset.setRejectedAt(Instant.now());
            asset.setFailureReasonCode("MALWARE_DETECTED");
            repository.saveAndFlush(asset);
            audit(actor, asset, "MALWARE_DETECTED", "DENIED", "SCANNER_DETECTED", null);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The file was rejected by malware scanning."
            );
        }
        asset.setStatus(MediaStatus.QUARANTINED);
        asset.setScanningStatus(
                scan == MalwareScanner.ScanResult.UNAVAILABLE
                        ? ScanningStatus.UNAVAILABLE : ScanningStatus.FAILED
        );
        asset.setFailureReasonCode(
                scan == MalwareScanner.ScanResult.UNAVAILABLE
                        ? "MALWARE_SCANNER_UNAVAILABLE" : "MALWARE_SCAN_FAILED"
        );
        MediaAsset saved = repository.saveAndFlush(asset);
        audit(
                actor, saved,
                scan == MalwareScanner.ScanResult.UNAVAILABLE
                        ? "UPLOAD_QUARANTINED" : "MALWARE_SCAN_FAILED",
                "PENDING",
                saved.getFailureReasonCode(),
                null
        );
        return map(saved);
    }

    private MediaAsset baseAsset(
            UUID id,
            User actor,
            UploadPurpose purpose,
            DetectedMedia detected
    ) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setOwner(actor);
        asset.setCreatedBy(actor);
        asset.setPurpose(purpose);
        asset.setStatus(MediaStatus.PENDING);
        asset.setProvider("CLOUDINARY");
        asset.setResourceType(purpose.resourceType(detected.format()));
        asset.setDeliveryType(purpose.deliveryType());
        asset.setOriginalFilename(detected.safeFilename());
        asset.setDetectedMimeType(detected.mimeType());
        asset.setDetectedFormat(detected.format());
        asset.setSizeBytes(detected.sizeBytes());
        asset.setChecksumSha256(detected.checksumSha256());
        asset.setWidth(detected.width());
        asset.setHeight(detected.height());
        asset.setFrameCount(detected.frameCount());
        asset.setPrivateAsset(purpose.privateAsset());
        asset.setScanningStatus(
                purpose.malwareScanRequired() ? ScanningStatus.PENDING : ScanningStatus.NOT_REQUIRED
        );
        return asset;
    }

    private void applyProvider(MediaAsset asset, CloudinaryGateway.ProviderAsset provider) {
        asset.setProviderAssetId(provider.assetId());
        asset.setProviderPublicId(provider.publicId());
        asset.setResourceType(provider.resourceType());
        asset.setDeliveryType(provider.deliveryType());
        asset.setProviderSecureUrl(provider.secureUrl());
    }

    private Path spool(MultipartFile file, long purposeLimit) {
        if (file == null || file.isEmpty()) {
            throw new MediaValidationException("EMPTY_FILE", "Please choose a nonempty file.");
        }
        if (file.getSize() > purposeLimit || file.getSize() > ABSOLUTE_MAXIMUM) {
            throw new MediaValidationException("FILE_TOO_LARGE", "The file exceeds the upload limit.");
        }
        Path path = null;
        try {
            path = Files.createTempFile("leaflet-media-", ".upload");
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX development systems retain createTempFile's protected defaults.
            }
            try (InputStream input = file.getInputStream();
                 var output = Files.newOutputStream(path)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > purposeLimit || total > ABSOLUTE_MAXIMUM) {
                        throw new MediaValidationException(
                                "FILE_TOO_LARGE",
                                "The streamed file exceeds the upload limit."
                        );
                    }
                    output.write(buffer, 0, read);
                }
            }
            return path;
        } catch (MediaValidationException exception) {
            deleteTemporaryBestEffort(path);
            throw exception;
        } catch (Exception exception) {
            deleteTemporaryBestEffort(path);
            throw new MediaValidationException(
                    "TEMPORARY_STORAGE_FAILED",
                    "The file could not be safely staged for validation."
            );
        }
    }

    private void deleteTemporaryBestEffort(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The operating system temp cleaner is the final fallback.
        }
    }

    private void authorizePurpose(User actor, UploadPurpose purpose) {
        if (!purpose.canUpload(actor.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Your role cannot upload media for this purpose."
            );
        }
    }

    private void enforceRateLimits(
            User actor,
            UploadPurpose purpose,
            RequestMetadata metadata
    ) {
        boolean userAllowed = rateLimitService.consume(
                "media:" + purpose.name(),
                "user",
                String.valueOf(actor.getId()),
                Duration.ofHours(1),
                Math.min(properties.getUserHourlyLimit(), purpose.hourlyAttempts())
        );
        boolean ipAllowed = rateLimitService.consume(
                "media:" + purpose.name(),
                "ip",
                metadata == null ? "unknown" : metadata.clientIp(),
                Duration.ofHours(1),
                properties.getIpHourlyLimit()
        );
        if (!userAllowed || !ipAllowed) {
            audit(
                    actor, null, "UPLOAD_RATE_LIMITED", "DENIED",
                    "ROLLING_HOURLY_LIMIT", "purpose=" + purpose
            );
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "The upload rate limit has been reached."
            );
        }
    }

    private void enforceQuotas(User actor, UploadPurpose purpose, long submittedBytes) {
        if (repository.countByOwnerIdAndStatusIn(actor.getId(), PENDING)
                >= properties.getPendingLimit()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Resolve or remove pending uploads before uploading another file."
            );
        }
        long stored = repository.sumStoredBytes(actor.getId(), purpose, STORED);
        if (stored + Math.max(submittedBytes, 0) > properties.getStoredBytesPerPurpose()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "The stored-byte quota for this upload purpose has been reached."
            );
        }
    }

    private MediaAssetResponse map(MediaAsset asset) {
        return new MediaAssetResponse(
                asset.getId(),
                asset.getPurpose(),
                asset.getStatus(),
                asset.getScanningStatus(),
                asset.getOriginalFilename(),
                asset.getDetectedMimeType(),
                asset.getDetectedFormat(),
                asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.isPrivateAsset() ? null : asset.getProviderSecureUrl(),
                asset.getCreatedAt()
        );
    }

    private void audit(
            User actor,
            MediaAsset asset,
            String event,
            String outcome,
            String reason,
            String extra
    ) {
        String details = "assetId=%s,purpose=%s%s".formatted(
                asset == null ? "pending" : asset.getId(),
                asset == null ? "unknown" : asset.getPurpose(),
                extra == null ? "" : "," + extra
        );
        auditService.recordWithDetails(
                actor == null ? null : actor.getId(),
                asset == null ? null : asset.getOwner().getId(),
                event,
                outcome,
                reason,
                details,
                null,
                RequestMetadata.current()
        );
    }

    private void deleteProviderBestEffort(CloudinaryGateway.ProviderAsset provider) {
        try {
            cloudinaryGateway.delete(
                    provider.resourceType(),
                    provider.deliveryType(),
                    provider.publicId()
            );
        } catch (RuntimeException ignored) {
            // A cleanup job can retry using provider logs; the original failure is preserved.
        }
    }
}
