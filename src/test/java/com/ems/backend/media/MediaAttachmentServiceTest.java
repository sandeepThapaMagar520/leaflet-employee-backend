package com.ems.backend.media;

import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
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
import static org.mockito.Mockito.when;

class MediaAttachmentServiceTest {
    private final MediaAssetRepository repository = mock(MediaAssetRepository.class);
    private final CloudinaryGateway gateway = mock(CloudinaryGateway.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private MediaAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new MediaAttachmentService(repository, gateway, audit);
        when(repository.save(any(MediaAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void attachesVerifiedOwnedAssetToMatchingPurpose() {
        User owner = user(1L, Role.EMPLOYEE);
        MediaAsset asset = asset(owner, UploadPurpose.TASK_ATTACHMENT, MediaStatus.VERIFIED);
        when(repository.findByIdForUpdate(asset.getId())).thenReturn(Optional.of(asset));

        MediaAsset result = service.attach(
                asset.getId(), UploadPurpose.TASK_ATTACHMENT, owner, owner,
                "TASK_COMMENT", "12"
        );

        assertEquals(MediaStatus.ATTACHED, result.getStatus());
        assertEquals("TASK_COMMENT", result.getAttachedResourceType());
    }

    @Test
    void rejectsCrossUserWrongPurposeAndNonVerifiedAssets() {
        User owner = user(1L, Role.EMPLOYEE);
        User attacker = user(2L, Role.EMPLOYEE);
        MediaAsset crossUser = asset(
                owner, UploadPurpose.TASK_ATTACHMENT, MediaStatus.VERIFIED
        );
        when(repository.findByIdForUpdate(crossUser.getId()))
                .thenReturn(Optional.of(crossUser));
        assertStatus(
                403,
                () -> service.attach(
                        crossUser.getId(), UploadPurpose.TASK_ATTACHMENT,
                        attacker, attacker, "TASK_COMMENT", "1"
                )
        );

        MediaAsset wrongPurpose = asset(
                owner, UploadPurpose.PROFILE_IMAGE, MediaStatus.VERIFIED
        );
        when(repository.findByIdForUpdate(wrongPurpose.getId()))
                .thenReturn(Optional.of(wrongPurpose));
        assertStatus(
                403,
                () -> service.attach(
                        wrongPurpose.getId(), UploadPurpose.TASK_ATTACHMENT,
                        owner, owner, "TASK_COMMENT", "1"
                )
        );

        for (MediaStatus status : new MediaStatus[]{
                MediaStatus.PENDING, MediaStatus.QUARANTINED,
                MediaStatus.REJECTED, MediaStatus.DELETED
        }) {
            MediaAsset unavailable = asset(owner, UploadPurpose.TASK_ATTACHMENT, status);
            when(repository.findByIdForUpdate(unavailable.getId()))
                    .thenReturn(Optional.of(unavailable));
            assertStatus(
                    409,
                    () -> service.attach(
                            unavailable.getId(), UploadPurpose.TASK_ATTACHMENT,
                            owner, owner, "TASK_COMMENT", "1"
                    )
            );
        }
    }

    private User user(long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private MediaAsset asset(User owner, UploadPurpose purpose, MediaStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId(UUID.randomUUID());
        asset.setOwner(owner);
        asset.setPurpose(purpose);
        asset.setStatus(status);
        return asset;
    }

    private void assertStatus(int expected, Runnable operation) {
        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(expected, exception.getStatusCode().value());
    }
}
