package com.ems.backend.media;

import com.ems.backend.auth.DatabaseRateLimitService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.config.MediaProperties;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadServiceTest {
    private final MediaAssetRepository repository = mock(MediaAssetRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SecurityUtils securityUtils = mock(SecurityUtils.class);
    private final MediaContentInspector inspector = mock(MediaContentInspector.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final CloudinaryGateway gateway = mock(CloudinaryGateway.class);
    private final DatabaseRateLimitService rateLimits = mock(DatabaseRateLimitService.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final MediaProperties properties = new MediaProperties();
    private MediaUploadService service;
    private User employee;
    private DetectedMedia png;

    @BeforeEach
    void setUp() {
        service = new MediaUploadService(
                repository, userRepository, securityUtils, inspector, scanner,
                gateway, rateLimits, properties, audit
        );
        employee = new User();
        employee.setId(10L);
        employee.setRole(Role.EMPLOYEE);
        png = new DetectedMedia(
                "image/png", "png", 8, "checksum", 128, 128, 1, "photo.png"
        );
        when(securityUtils.getCurrentUser()).thenReturn(employee);
        when(userRepository.findByIdForUpdate(employee.getId()))
                .thenReturn(Optional.of(employee));
        when(rateLimits.consume(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(true);
        when(repository.countByOwnerIdAndStatusIn(anyLong(), any())).thenReturn(0L);
        when(repository.sumStoredBytes(anyLong(), any(), any())).thenReturn(0L);
        when(inspector.inspect(any(), any(), any(), any())).thenReturn(png);
        when(repository.saveAndFlush(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void employeeCanUploadOwnVerifiedProfileImage() {
        when(gateway.upload(any(), any(), any(), anyString())).thenReturn(provider());

        var result = service.upload(
                UploadPurpose.PROFILE_IMAGE,
                new MockMultipartFile("file", "photo.png", "image/png", new byte[8])
        );

        assertEquals(MediaStatus.VERIFIED, result.status());
        assertEquals(UploadPurpose.PROFILE_IMAGE, result.purpose());
        verify(gateway).upload(any(), any(), any(), anyString());
    }

    @Test
    void requiredScanFailureQuarantinesAndNeverUploadsToProvider() {
        when(scanner.scan(any())).thenReturn(MalwareScanner.ScanResult.UNAVAILABLE);

        var result = service.upload(
                UploadPurpose.TASK_ATTACHMENT,
                new MockMultipartFile("file", "photo.png", "image/png", new byte[8])
        );

        assertEquals(MediaStatus.QUARANTINED, result.status());
        verify(gateway, never()).upload(any(), any(), any(), anyString());
    }

    @Test
    void sharedRateAndPendingQuotasFailBeforeProviderUpload() {
        when(rateLimits.consume(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(false);
        assertStatus(
                429,
                () -> service.upload(
                        UploadPurpose.PROFILE_IMAGE,
                        new MockMultipartFile(
                                "file", "photo.png", "image/png", new byte[8]
                        )
                )
        );

        when(rateLimits.consume(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(true);
        when(repository.countByOwnerIdAndStatusIn(anyLong(), any()))
                .thenReturn((long) properties.getPendingLimit());
        assertStatus(
                429,
                () -> service.upload(
                        UploadPurpose.PROFILE_IMAGE,
                        new MockMultipartFile(
                                "file", "photo.png", "image/png", new byte[8]
                        )
                )
        );
        verify(gateway, never()).upload(any(), any(), any(), anyString());
    }

    @Test
    void providerAssetIsDeletedWhenCanonicalDatabaseWriteFails() {
        CloudinaryGateway.ProviderAsset provider = provider();
        when(gateway.upload(any(), any(), any(), anyString())).thenReturn(provider);
        when(repository.saveAndFlush(any(MediaAsset.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.upload(
                        UploadPurpose.PROFILE_IMAGE,
                        new MockMultipartFile(
                                "file", "photo.png", "image/png", new byte[8]
                        )
                )
        );

        verify(gateway).delete(
                provider.resourceType(), provider.deliveryType(), provider.publicId()
        );
    }

    private CloudinaryGateway.ProviderAsset provider() {
        return new CloudinaryGateway.ProviderAsset(
                "asset-1",
                "leaflet/profile/id",
                "image",
                "upload",
                "https://res.cloudinary.com/cloud/image/upload/id.png",
                "png",
                8,
                128,
                128,
                Instant.now()
        );
    }

    private void assertStatus(int status, Runnable operation) {
        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(status, exception.getStatusCode().value());
    }
}
