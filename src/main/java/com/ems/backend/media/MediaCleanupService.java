package com.ems.backend.media;

import com.ems.backend.security.SecurityAuditService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MediaCleanupService {
    private static final int BATCH_SIZE = 100;

    private final MediaAssetRepository repository;
    private final CloudinaryGateway gateway;
    private final SecurityAuditService auditService;

    public MediaCleanupService(
            MediaAssetRepository repository,
            CloudinaryGateway gateway,
            SecurityAuditService auditService
    ) {
        this.repository = repository;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    @Scheduled(cron = "${app.media.cleanup-cron:0 17 * * * *}")
    @Transactional
    public void cleanupUnattachedAssets() {
        Instant now = Instant.now();
        repository.findCleanupCandidates(now.minusSeconds(4 * 3600L), BATCH_SIZE)
                .stream()
                .filter(asset -> asset.getCreatedAt().isBefore(
                        now.minusSeconds(asset.getPurpose().unattachedRetentionHours() * 3600L)
                ))
                .forEach(asset -> delete(asset, now));
    }

    private void delete(MediaAsset asset, Instant now) {
        if (asset.getProviderPublicId() != null) {
            try {
                gateway.delete(
                        asset.getResourceType(),
                        asset.getDeliveryType(),
                        asset.getProviderPublicId()
                );
            } catch (RuntimeException exception) {
                auditBestEffort(asset, "ORPHAN_PROVIDER_DELETE_FAILED", "FAILED");
                return;
            }
        }
        asset.setStatus(MediaStatus.DELETED);
        asset.setDeletedAt(now);
        asset.setFailureReasonCode("UNATTACHED_RETENTION_EXPIRED");
        repository.save(asset);
        auditBestEffort(asset, "MEDIA_DELETED", "SUCCESS");
    }

    private void auditBestEffort(MediaAsset asset, String event, String outcome) {
        try {
            auditService.recordWithDetails(
                    null,
                    asset.getOwner().getId(),
                    event,
                    outcome,
                    "UNATTACHED_CLEANUP",
                    "assetId=%s,purpose=%s".formatted(
                            asset.getId(), asset.getPurpose()
                    ),
                    null,
                    null
            );
        } catch (RuntimeException ignored) {
            // Cleanup progress must not be blocked by an unavailable audit sink.
        }
    }
}
