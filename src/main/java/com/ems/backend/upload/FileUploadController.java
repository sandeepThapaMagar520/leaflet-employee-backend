package com.ems.backend.upload;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Uploads", description = "Authenticated Cloudinary file upload")
public class FileUploadController {
    private final CloudinaryUploadService cloudinaryUploadService;

    public FileUploadController(CloudinaryUploadService cloudinaryUploadService) {
        this.cloudinaryUploadService = cloudinaryUploadService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Upload file", description = "Uploads a multipart file to Cloudinary and returns the hosted URL.")
    public CloudinaryUploadService.UploadResponse upload(@RequestParam("file") MultipartFile file) {
        return cloudinaryUploadService.upload(file);
    }
}
