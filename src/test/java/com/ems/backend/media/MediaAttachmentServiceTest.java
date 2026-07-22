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
    void purposeAwareValidationStatesControlAttachment() {
        User owner = user(1L, Role.EMPLOYEE);
        for (ScanningStatus allowed : new ScanningStatus[]{
                ScanningStatus.STRUCTURE_VALIDATED, ScanningStatus.CLEAN
        }) {
            MediaAsset pdf = asset(owner, UploadPurpose.TASK_ATTACHMENT, MediaStatus.VERIFIED);
            pdf.setDetectedFormat("pdf");
            pdf.setScanningStatus(allowed);
            when(repository.findByIdForUpdate(pdf.getId())).thenReturn(Optional.of(pdf));

            assertEquals(MediaStatus.ATTACHED, service.attach(
                    pdf.getId(), UploadPurpose.TASK_ATTACHMENT, owner, owner,
                    "TASK_COMMENT", "12"
            ).getStatus());
        }

        for (ScanningStatus denied : new ScanningStatus[]{
                ScanningStatus.PENDING, ScanningStatus.MALWARE_DETECTED,
                ScanningStatus.FAILED, ScanningStatus.UNAVAILABLE,
                ScanningStatus.NOT_REQUIRED
        }) {
            MediaAsset pdf = asset(owner, UploadPurpose.TASK_ATTACHMENT, MediaStatus.VERIFIED);
            pdf.setDetectedFormat("pdf");
            pdf.setScanningStatus(denied);
            when(repository.findByIdForUpdate(pdf.getId())).thenReturn(Optional.of(pdf));

            assertStatus(409, () -> service.attach(
                    pdf.getId(), UploadPurpose.TASK_ATTACHMENT, owner, owner,
                    "TASK_COMMENT", "12"
            ));
        }
    }

    @Test
    void structurallyValidatedPdfCanAttachToEveryDocumentPurpose() {
        User employee = user(1L, Role.EMPLOYEE);
        User manager = user(2L, Role.MANAGER);
        User administrator = user(3L, Role.ADMIN);

        assertPdfAttachable(
                UploadPurpose.PROJECT_ATTACHMENT, employee, employee, "PROJECT", "10"
        );
        assertPdfAttachable(
                UploadPurpose.TASK_ATTACHMENT, employee, employee, "TASK_COMMENT", "11"
        );
        assertPdfAttachable(
                UploadPurpose.PAYMENT_ATTACHMENT, manager, manager,
                "PROJECT_PAYMENT", "12"
        );
        assertPdfAttachable(
                UploadPurpose.HR_DOCUMENT, administrator, employee,
                "STAFF_DOCUMENT", "13"
        );
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
        asset.setDetectedFormat(purpose == UploadPurpose.HR_DOCUMENT ? "pdf" : "png");
        asset.setScanningStatus(
                purpose == UploadPurpose.HR_DOCUMENT
                        ? ScanningStatus.STRUCTURE_VALIDATED
                        : ScanningStatus.NOT_REQUIRED
        );
        return asset;
    }

    private void assertPdfAttachable(
            UploadPurpose purpose,
            User actor,
            User targetOwner,
            String resourceType,
            String resourceId
    ) {
        MediaAsset pdf = asset(actor, purpose, MediaStatus.VERIFIED);
        pdf.setDetectedFormat("pdf");
        pdf.setScanningStatus(ScanningStatus.STRUCTURE_VALIDATED);
        when(repository.findByIdForUpdate(pdf.getId())).thenReturn(Optional.of(pdf));

        MediaAsset attached = service.attach(
                pdf.getId(), purpose, actor, targetOwner, resourceType, resourceId
        );

        assertEquals(MediaStatus.ATTACHED, attached.getStatus());
        assertEquals(targetOwner.getId(), attached.getOwner().getId());
    }

    private void assertStatus(int expected, Runnable operation) {
        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(expected, exception.getStatusCode().value());
    }
}
