package com.ems.backend.media;

import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.config.MediaProperties;
import com.ems.backend.project.*;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.task.TaskCommentRepository;
import com.ems.backend.user.StaffDocumentRepository;
import com.ems.backend.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class MediaDeliveryService {
    private final MediaAssetRepository mediaRepository;
    private final StaffDocumentRepository staffDocumentRepository;
    private final ProjectPaymentAttachmentRepository paymentAttachmentRepository;
    private final ProjectNoteMediaAttachmentRepository noteAttachmentRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final ProjectRepository projectRepository;
    private final SecurityUtils securityUtils;
    private final AuthorizationPolicyService authorizationPolicy;
    private final ProjectAccessService projectAccessService;
    private final CloudinaryGateway gateway;
    private final MediaProperties properties;
    private final SecurityAuditService auditService;

    public MediaDeliveryService(
            MediaAssetRepository mediaRepository,
            StaffDocumentRepository staffDocumentRepository,
            ProjectPaymentAttachmentRepository paymentAttachmentRepository,
            ProjectNoteMediaAttachmentRepository noteAttachmentRepository,
            TaskCommentRepository taskCommentRepository,
            ProjectRepository projectRepository,
            SecurityUtils securityUtils,
            AuthorizationPolicyService authorizationPolicy,
            ProjectAccessService projectAccessService,
            CloudinaryGateway gateway,
            MediaProperties properties,
            SecurityAuditService auditService
    ) {
        this.mediaRepository = mediaRepository;
        this.staffDocumentRepository = staffDocumentRepository;
        this.paymentAttachmentRepository = paymentAttachmentRepository;
        this.noteAttachmentRepository = noteAttachmentRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.projectRepository = projectRepository;
        this.securityUtils = securityUtils;
        this.authorizationPolicy = authorizationPolicy;
        this.projectAccessService = projectAccessService;
        this.gateway = gateway;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DownloadGrant grant(UUID assetId) {
        User actor = securityUtils.getCurrentUser();
        MediaAsset asset = mediaRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Media asset not found."
                ));
        if (asset.getStatus() != MediaStatus.ATTACHED || !asset.isPrivateAsset()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Private attached media was not found."
            );
        }
        authorizeParent(actor, asset);
        Instant expiresAt = Instant.now().plusSeconds(properties.getPrivateDownloadTtlSeconds());
        String url = gateway.privateDownloadUrl(
                asset.getResourceType(),
                asset.getDeliveryType(),
                asset.getProviderPublicId(),
                asset.getDetectedFormat(),
                expiresAt
        );
        auditService.recordWithDetails(
                actor.getId(),
                asset.getOwner().getId(),
                asset.getPurpose() == UploadPurpose.HR_DOCUMENT
                        ? "PRIVATE_DOCUMENT_DOWNLOADED" : "PRIVATE_MEDIA_DOWNLOADED",
                "SUCCESS",
                asset.getPurpose().name(),
                "assetId=" + asset.getId(),
                null,
                RequestMetadata.current()
        );
        return new DownloadGrant(url, expiresAt, asset.getOriginalFilename());
    }

    private void authorizeParent(User actor, MediaAsset asset) {
        switch (asset.getPurpose()) {
            case HR_DOCUMENT -> {
                var document = staffDocumentRepository.findByMediaAssetId(asset.getId())
                        .orElseThrow(() -> notFound());
                if (!authorizationPolicy.canViewHrDocument(actor, document.getUser(), document)) {
                    denied(actor, asset);
                }
            }
            case PAYMENT_ATTACHMENT -> {
                var attachment = paymentAttachmentRepository.findByMediaAssetId(asset.getId())
                        .orElseThrow(() -> notFound());
                if (!projectAccessService.canViewProjectFinancials(
                        actor, attachment.getPayment().getProject()
                )) denied(actor, asset);
            }
            case TASK_ATTACHMENT -> {
                var comment = taskCommentRepository.findByMediaAssetId(asset.getId())
                        .orElseThrow(() -> notFound());
                if (!projectAccessService.canAccessProject(actor, comment.getTask().getProject())) {
                    denied(actor, asset);
                }
            }
            case PROJECT_ATTACHMENT -> {
                var note = noteAttachmentRepository.findByMediaAssetId(asset.getId());
                Project project = note.map(value -> value.getNote().getProject())
                        .orElseGet(() -> projectRepository.findByDocumentMediaAssetId(asset.getId())
                                .orElseThrow(() -> notFound()));
                if (!projectAccessService.canAccessProject(actor, project)) denied(actor, asset);
            }
            case PROFILE_IMAGE -> throw notFound();
        }
    }

    private void denied(User actor, MediaAsset asset) {
        auditService.recordWithDetails(
                actor.getId(),
                asset.getOwner().getId(),
                "CROSS_OWNER_MEDIA_ACCESS_DENIED",
                "DENIED",
                "PRIVATE_DOWNLOAD",
                "assetId=" + asset.getId(),
                null,
                RequestMetadata.current()
        );
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You do not have permission to download this media."
        );
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Media attachment not found.");
    }

    public record DownloadGrant(String url, Instant expiresAt, String filename) {}
}
