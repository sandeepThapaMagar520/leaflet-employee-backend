package com.ems.backend.media;

import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class MediaAttachmentService {
    private final MediaAssetRepository repository;
    private final CloudinaryGateway gateway;
    private final SecurityAuditService auditService;

    public MediaAttachmentService(
            MediaAssetRepository repository,
            CloudinaryGateway gateway,
            SecurityAuditService auditService
    ) {
        this.repository = repository;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    @Transactional
    public MediaAsset attach(
            UUID assetId,
            UploadPurpose purpose,
            User actor,
            User targetOwner,
            String resourceType,
            String resourceId
    ) {
        MediaAsset asset = repository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Media asset not found."
                ));
        if (asset.getStatus() == MediaStatus.ATTACHED
                && resourceType.equals(asset.getAttachedResourceType())
                && resourceId.equals(asset.getAttachedResourceId())) {
            return asset;
        }
        if (asset.getStatus() != MediaStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only verified, unattached media can be attached."
            );
        }
        if (asset.getPurpose() != purpose) {
            deny(actor, asset, "WRONG_PURPOSE");
        }
        if (!purpose.attachmentTargets().contains(resourceType)) {
            deny(actor, asset, "WRONG_ATTACHMENT_TARGET");
        }
        boolean administratorHrBinding =
                purpose == UploadPurpose.HR_DOCUMENT && actor.getRole() == Role.ADMIN;
        if (!asset.getOwner().getId().equals(actor.getId()) && !administratorHrBinding) {
            deny(actor, asset, "CROSS_OWNER_ATTACHMENT");
        }
        if (administratorHrBinding && targetOwner != null) {
            asset.setOwner(targetOwner);
        }
        asset.setStatus(MediaStatus.ATTACHED);
        asset.setAttachedAt(Instant.now());
        asset.setAttachedResourceType(resourceType);
        asset.setAttachedResourceId(resourceId);
        MediaAsset saved = repository.save(asset);
        auditService.recordWithDetails(
                actor.getId(),
                saved.getOwner().getId(),
                "MEDIA_ATTACHED",
                "SUCCESS",
                purpose.name(),
                "assetId=%s,parent=%s:%s".formatted(assetId, resourceType, resourceId),
                null,
                RequestMetadata.current()
        );
        return saved;
    }

    @Transactional
    public void deleteAttached(MediaAsset supplied, User actor, String reason) {
        if (supplied == null) return;
        MediaAsset asset = repository.findByIdForUpdate(supplied.getId()).orElse(null);
        if (asset == null || asset.getStatus() == MediaStatus.DELETED) return;
        if (asset.getProviderPublicId() != null) {
            gateway.delete(
                    asset.getResourceType(),
                    asset.getDeliveryType(),
                    asset.getProviderPublicId()
            );
        }
        asset.setStatus(MediaStatus.DELETED);
        asset.setDeletedAt(Instant.now());
        asset.setFailureReasonCode(reason);
        repository.save(asset);
        auditService.recordWithDetails(
                actor.getId(),
                asset.getOwner().getId(),
                "MEDIA_DELETED",
                "SUCCESS",
                reason,
                "assetId=%s,parent=%s:%s".formatted(
                        asset.getId(),
                        asset.getAttachedResourceType(),
                        asset.getAttachedResourceId()
                ),
                null,
                RequestMetadata.current()
        );
    }

    private void deny(User actor, MediaAsset asset, String reason) {
        auditService.recordWithDetails(
                actor.getId(),
                asset.getOwner().getId(),
                "CROSS_OWNER_MEDIA_ACCESS_DENIED",
                "DENIED",
                reason,
                "assetId=" + asset.getId(),
                null,
                RequestMetadata.current()
        );
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "This media asset cannot be attached to the requested record."
        );
    }
}
