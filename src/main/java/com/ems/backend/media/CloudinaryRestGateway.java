package com.ems.backend.media;

import com.ems.backend.config.CloudinaryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@Service
public class CloudinaryRestGateway implements CloudinaryGateway {
    private final CloudinaryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client = RestClient.create();

    public CloudinaryRestGateway(
            CloudinaryProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProviderAsset upload(
            Path file,
            UploadPurpose purpose,
            DetectedMedia detected,
            String expectedPublicId
    ) {
        requireConfigured();
        String resourceType = purpose.resourceType(detected.format());
        String endpoint = "https://api.cloudinary.com/v1_1/%s/%s/upload"
                .formatted(properties.getCloudName(), resourceType);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        body.add("public_id", expectedPublicId);
        body.add("type", purpose.deliveryType());
        body.add("overwrite", "false");
        body.add("use_filename", "false");
        body.add("unique_filename", "false");
        body.add("resource_type", resourceType);
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> signed = new TreeMap<>();
        signed.put("overwrite", "false");
        signed.put("public_id", expectedPublicId);
        signed.put("timestamp", String.valueOf(timestamp));
        signed.put("type", purpose.deliveryType());
        signed.put("unique_filename", "false");
        signed.put("use_filename", "false");
        body.add("timestamp", String.valueOf(timestamp));
        body.add("api_key", properties.getApiKey());
        body.add("signature", sign(signed));
        try {
            String raw = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return validateResponse(
                    objectMapper.readTree(raw),
                    purpose,
                    detected,
                    expectedPublicId,
                    resourceType
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new ResponseStatusException(TOO_MANY_REQUESTS, "The media provider is rate limited.");
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw new ResponseStatusException(SERVICE_UNAVAILABLE, "The media provider is temporarily unavailable.");
            }
            throw new ResponseStatusException(BAD_GATEWAY, "The media provider rejected the upload.");
        } catch (Exception exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "The media provider could not be reached.");
        }
    }

    @Override
    public void delete(String resourceType, String deliveryType, String publicId) {
        requireConfigured();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("public_id", publicId);
        body.add("type", deliveryType);
        body.add("invalidate", "true");
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> signed = new TreeMap<>();
        signed.put("invalidate", "true");
        signed.put("public_id", publicId);
        signed.put("timestamp", String.valueOf(timestamp));
        signed.put("type", deliveryType);
        body.add("timestamp", String.valueOf(timestamp));
        body.add("api_key", properties.getApiKey());
        body.add("signature", sign(signed));
        try {
            String raw = client.post()
                    .uri("https://api.cloudinary.com/v1_1/%s/%s/destroy"
                            .formatted(properties.getCloudName(), resourceType))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String result = objectMapper.readTree(raw).path("result").asText();
            if (!"ok".equals(result) && !"not found".equals(result)) {
                throw new ResponseStatusException(BAD_GATEWAY, "The provider did not confirm media deletion.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Provider media deletion failed.");
        }
    }

    @Override
    public String privateDownloadUrl(
            String resourceType,
            String deliveryType,
            String publicId,
            String format,
            Instant expiresAt
    ) {
        requireConfigured();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("expires_at", String.valueOf(expiresAt.getEpochSecond()));
        parameters.put("format", format);
        parameters.put("public_id", publicId);
        parameters.put("resource_type", resourceType);
        parameters.put("timestamp", String.valueOf(timestamp));
        parameters.put("type", deliveryType);
        String signature = sign(parameters);
        Map<String, String> query = new LinkedHashMap<>(parameters);
        query.put("signature", signature);
        query.put("api_key", properties.getApiKey());
        return "https://api.cloudinary.com/v1_1/%s/%s/download?%s".formatted(
                properties.getCloudName(),
                resourceType,
                query.entrySet().stream()
                        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                        .collect(Collectors.joining("&"))
        );
    }

    private String sign(Map<String, String> parameters) {
        String toSign = new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(
                            (toSign + properties.getApiSecret())
                                    .getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign media provider request.", exception);
        }
    }

    ProviderAsset validateResponse(
            JsonNode json,
            UploadPurpose purpose,
            DetectedMedia detected,
            String expectedPublicId,
            String expectedResourceType
    ) {
        String assetId = required(json, "asset_id");
        String publicId = required(json, "public_id");
        String resourceType = required(json, "resource_type");
        String type = required(json, "type");
        String secureUrl = required(json, "secure_url");
        String format = json.path("format").asText(detected.format());
        long bytes = json.path("bytes").asLong(-1);
        Instant createdAt;
        try {
            createdAt = Instant.parse(required(json, "created_at"));
        } catch (Exception exception) {
            throw providerRejected("Provider creation time is invalid.");
        }
        URI uri;
        try {
            uri = URI.create(secureUrl);
        } catch (Exception exception) {
            throw providerRejected("Provider URL is invalid.");
        }
        String expectedPath = "/%s/%s/%s/".formatted(
                properties.getCloudName(),
                expectedResourceType,
                purpose.deliveryType()
        );
        if (!expectedPublicId.equals(publicId)
                || !expectedResourceType.equals(resourceType)
                || !purpose.deliveryType().equals(type)
                || !"https".equals(uri.getScheme())
                || !"res.cloudinary.com".equals(uri.getHost())
                || !uri.getPath().contains(expectedPath)
                || bytes != detected.sizeBytes()
                || json.path("overwritten").asBoolean(false)
                || json.path("existing").asBoolean(false)
                || createdAt.isBefore(Instant.now().minus(10, ChronoUnit.MINUTES))
                || createdAt.isAfter(Instant.now().plus(2, ChronoUnit.MINUTES))) {
            throw providerRejected("Provider identity or metadata did not match the upload intent.");
        }
        if (!"raw".equals(resourceType) && !detected.format().equals(format)) {
            throw providerRejected("Provider format did not match inspected content.");
        }
        Integer width = json.hasNonNull("width") ? json.get("width").asInt() : null;
        Integer height = json.hasNonNull("height") ? json.get("height").asInt() : null;
        if ("image".equals(resourceType)
                && (!detected.width().equals(width) || !detected.height().equals(height))) {
            throw providerRejected("Provider image dimensions did not match inspected content.");
        }
        return new ProviderAsset(
                assetId, publicId, resourceType, type, secureUrl, format,
                bytes, width, height, createdAt
        );
    }

    private String required(JsonNode json, String field) {
        if (!json.hasNonNull(field) || json.get(field).asText().isBlank()) {
            throw providerRejected("Provider response omitted " + field + ".");
        }
        return json.get(field).asText();
    }

    private ResponseStatusException providerRejected(String reason) {
        return new ResponseStatusException(BAD_GATEWAY, reason);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "Secure media storage is not configured."
            );
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
