package com.ems.backend.media;

import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.common.ProjectAccessService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.config.MediaProperties;
import com.ems.backend.project.ProjectNoteMediaAttachmentRepository;
import com.ems.backend.project.ProjectPaymentAttachmentRepository;
import com.ems.backend.project.ProjectRepository;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.task.TaskCommentRepository;
import com.ems.backend.user.Role;
import com.ems.backend.user.StaffDocument;
import com.ems.backend.user.StaffDocumentRepository;
import com.ems.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaDeliveryServiceTest {
    private final MediaAssetRepository mediaRepository = mock(MediaAssetRepository.class);
    private final StaffDocumentRepository documentRepository = mock(StaffDocumentRepository.class);
    private final SecurityUtils securityUtils = mock(SecurityUtils.class);
    private final AuthorizationPolicyService authorizationPolicy =
            mock(AuthorizationPolicyService.class);
    private final CloudinaryGateway gateway = mock(CloudinaryGateway.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private MediaDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new MediaDeliveryService(
                mediaRepository,
                documentRepository,
                mock(ProjectPaymentAttachmentRepository.class),
                mock(ProjectNoteMediaAttachmentRepository.class),
                mock(TaskCommentRepository.class),
                mock(ProjectRepository.class),
                securityUtils,
                authorizationPolicy,
                mock(ProjectAccessService.class),
                gateway,
                new MediaProperties(),
                audit
        );
    }

    @Test
    void ownerCanReceiveShortLivedPrivateGrantAndAuditIsWritten() {
        User owner = user(1L, Role.EMPLOYEE);
        MediaAsset asset = hrAsset(owner, MediaStatus.ATTACHED);
        StaffDocument document = document(owner, asset);
        when(securityUtils.getCurrentUser()).thenReturn(owner);
        when(mediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(documentRepository.findByMediaAssetId(asset.getId()))
                .thenReturn(Optional.of(document));
        when(authorizationPolicy.canViewHrDocument(owner, owner, document))
                .thenReturn(true);
        when(gateway.privateDownloadUrl(any(), any(), any(), any(), any()))
                .thenReturn("https://api.cloudinary.com/private");

        var grant = service.grant(asset.getId());

        assertEquals("employment-contract.pdf", grant.filename());
        verify(audit).recordWithDetails(
                any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void unscopedManagerAndDeletedAssetCannotDownload() {
        User owner = user(1L, Role.EMPLOYEE);
        User manager = user(2L, Role.MANAGER);
        MediaAsset asset = hrAsset(owner, MediaStatus.ATTACHED);
        StaffDocument document = document(owner, asset);
        when(securityUtils.getCurrentUser()).thenReturn(manager);
        when(mediaRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(documentRepository.findByMediaAssetId(asset.getId()))
                .thenReturn(Optional.of(document));
        when(authorizationPolicy.canViewHrDocument(manager, owner, document))
                .thenReturn(false);

        assertStatus(403, () -> service.grant(asset.getId()));

        MediaAsset deleted = hrAsset(owner, MediaStatus.DELETED);
        when(mediaRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
        assertStatus(404, () -> service.grant(deleted.getId()));
    }

    private User user(long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private MediaAsset hrAsset(User owner, MediaStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId(UUID.randomUUID());
        asset.setOwner(owner);
        asset.setPurpose(UploadPurpose.HR_DOCUMENT);
        asset.setStatus(status);
        asset.setPrivateAsset(true);
        asset.setResourceType("raw");
        asset.setDeliveryType("authenticated");
        asset.setProviderPublicId("leaflet/hr/" + asset.getId() + ".pdf");
        asset.setDetectedFormat("pdf");
        asset.setOriginalFilename("employment-contract.pdf");
        return asset;
    }

    private StaffDocument document(User owner, MediaAsset asset) {
        StaffDocument document = new StaffDocument();
        document.setUser(owner);
        document.setMediaAsset(asset);
        return document;
    }

    private void assertStatus(int status, Runnable operation) {
        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(status, exception.getStatusCode().value());
    }
}
