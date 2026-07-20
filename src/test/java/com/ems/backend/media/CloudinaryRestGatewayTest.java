package com.ems.backend.media;

import com.ems.backend.config.CloudinaryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudinaryRestGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloudinaryRestGateway gateway;
    private DetectedMedia detected;

    @BeforeEach
    void setUp() {
        CloudinaryProperties properties = new CloudinaryProperties();
        properties.setCloudName("leaflet");
        properties.setApiKey("api-key");
        properties.setApiSecret("a-secure-provider-secret");
        gateway = new CloudinaryRestGateway(properties, objectMapper);
        detected = new DetectedMedia(
                "image/png", "png", 1234, "abc", 128, 128, 1, "photo.png"
        );
    }

    @Test
    void acceptsOnlyCanonicalExpectedProviderMetadata() throws Exception {
        var result = gateway.validateResponse(
                objectMapper.readTree(validJson()),
                UploadPurpose.PROFILE_IMAGE,
                detected,
                "leaflet/profile/id",
                "image"
        );

        assertEquals("asset-1", result.assetId());
        assertEquals("leaflet/profile/id", result.publicId());
    }

    @Test
    void rejectsWrongIdentityResourceDeliveryFormatDimensionsHostAndOverwrite()
            throws Exception {
        assertRejected(validJson().replace(
                "\"leaflet/profile/id\"", "\"attacker/id\""
        ));
        assertRejected(validJson().replace("\"image\"", "\"raw\""));
        assertRejected(validJson().replace("\"upload\"", "\"authenticated\""));
        assertRejected(validJson().replace("\"format\":\"png\"", "\"format\":\"jpeg\""));
        assertRejected(validJson().replace("\"width\":128", "\"width\":129"));
        assertRejected(validJson().replace(
                "https://res.cloudinary.com", "https://evil.example"
        ));
        assertRejected(validJson().replace(
                "\"height\":128", "\"height\":128,\"overwritten\":true"
        ));
    }

    @Test
    void rejectsMalformedOrImplausibleProviderResponse() throws Exception {
        assertRejected("{}");
        assertRejected(validJson().replaceFirst(
                "\"created_at\":\"[^\"]+\"",
                "\"created_at\":\"2020-01-01T00:00:00Z\""
        ));
    }

    private void assertRejected(String json) throws Exception {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> gateway.validateResponse(
                        objectMapper.readTree(json),
                        UploadPurpose.PROFILE_IMAGE,
                        detected,
                        "leaflet/profile/id",
                        "image"
                )
        );
        assertEquals(502, exception.getStatusCode().value());
    }

    private String validJson() {
        return """
                {
                  "asset_id":"asset-1",
                  "public_id":"leaflet/profile/id",
                  "resource_type":"image",
                  "type":"upload",
                  "secure_url":"https://res.cloudinary.com/leaflet/image/upload/v1/leaflet/profile/id.png",
                  "format":"png",
                  "bytes":1234,
                  "width":128,
                  "height":128,
                  "created_at":"%s"
                }
                """.formatted(Instant.now());
    }
}
