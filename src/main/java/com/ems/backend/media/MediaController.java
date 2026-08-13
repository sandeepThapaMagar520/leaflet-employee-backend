package com.ems.backend.media;

import com.ems.backend.media.dto.MediaAssetResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
@Tag(name = "Media", description = "Purpose-bound authenticated media operations")
public class MediaController {
    private final MediaUploadService uploadService;
    private final MediaDeliveryService deliveryService;

    public MediaController(
            MediaUploadService uploadService,
            MediaDeliveryService deliveryService
    ) {
        this.uploadService = uploadService;
        this.deliveryService = deliveryService;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload exactly one purpose-bound file")
    public MediaAssetResponse upload(
            @RequestParam("purpose") UploadPurpose purpose,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) throws Exception {
        validateMultipartShape(request.getParts());
        return uploadService.upload(purpose, file);
    }

    @DeleteMapping("/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unattached owned media asset")
    public void delete(@PathVariable UUID assetId) {
        uploadService.deleteUnattached(assetId);
    }

    @org.springframework.web.bind.annotation.GetMapping("/assets/{assetId}/download")
    @Operation(summary = "Authorize and redirect to short-lived private delivery")
    public ResponseEntity<Void> download(@PathVariable UUID assetId) {
        var grant = deliveryService.grant(assetId);
        String safeName = grant.filename().replaceAll("[\\r\\n\"\\\\]", "_");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, grant.url())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\""
                )
                .build();
    }

    void validateMultipartShape(Collection<Part> parts) {
        Set<String> allowed = Set.of("purpose", "file");
        if (parts.stream().anyMatch(part -> !allowed.contains(part.getName()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The multipart request contains an unknown field."
            );
        }
        long files = parts.stream().filter(part -> "file".equals(part.getName())).count();
        long purposes = parts.stream().filter(part -> "purpose".equals(part.getName())).count();
        if (files != 1 || purposes != 1 || parts.size() != 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Exactly one purpose part and one file part are required."
            );
        }
    }
}
