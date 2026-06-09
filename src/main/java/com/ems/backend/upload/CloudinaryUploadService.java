package com.ems.backend.upload;

import com.ems.backend.config.CloudinaryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class CloudinaryUploadService {
    private final CloudinaryProperties cloudinaryProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CloudinaryUploadService(CloudinaryProperties cloudinaryProperties, ObjectMapper objectMapper) {
        this.cloudinaryProperties = cloudinaryProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public UploadResponse upload(MultipartFile file) {
        if (!cloudinaryProperties.isConfigured()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "File upload is not configured on the server.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Please choose a file to upload.");
        }

        String contentType = file.getContentType() != null ? file.getContentType() : "";
        String resourceType = contentType.startsWith("image/") ? "image" : "raw";
        String url = "https://api.cloudinary.com/v1_1/%s/%s/upload".formatted(
                cloudinaryProperties.getCloudName(),
                resourceType
        );

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());
            body.add("upload_preset", cloudinaryProperties.getUploadPreset());

            String responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode json = objectMapper.readTree(responseBody);
            if (json.hasNonNull("secure_url")) {
                return new UploadResponse(
                        json.get("secure_url").asText(),
                        json.path("public_id").asText(null),
                        json.path("resource_type").asText(resourceType),
                        json.path("bytes").asLong(0)
                );
            }
            throw new ResponseStatusException(BAD_REQUEST, "Cloudinary did not return a file URL.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not read the uploaded file.");
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, "File upload failed. Please try again.");
        }
    }

    public record UploadResponse(String url, String publicId, String resourceType, long bytes) {}
}
