package com.ems.backend.mail;

import com.ems.backend.config.MailProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsEmailThroughGoogleAppsScriptWebhook() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"success\":true}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EmailService emailService = new EmailService(mailProperties(), objectMapper, httpClient);

        assertEquals(EmailDeliveryResult.Outcome.ACCEPTED, emailService.deliver(
                "employee@example.com", "EMAIL_CHANGE_OTP",
                Map.of("fullName", "Employee", "otp", "123456"), UUID.randomUUID()
        ).outcome());
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void webhookPayloadIncludesHtmlAndPlainTextFallback() throws Exception {
        EmailService emailService = new EmailService(mailProperties(), objectMapper, mock(HttpClient.class));
        UUID idempotencyKey = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree(emailService.buildWebhookPayload(
                "employee@example.com",
                "Subject",
                "Plain text",
                "<strong>Formatted</strong>",
                idempotencyKey
        ));

        assertTrue(payload.path("body").asText().contains("Plain text"));
        assertTrue(payload.path("htmlBody").asText().contains("<strong>Formatted</strong>"));
        assertTrue(payload.path("fromName").asText().contains("Leaflet EMS"));
        assertEquals(idempotencyKey.toString(), payload.path("idempotencyKey").asText());
    }

    @Test
    void classifiesRateLimitAndServerErrorsAsRetryable() throws Exception {
        assertEquals(EmailDeliveryResult.Outcome.RETRYABLE_FAILURE, deliverWith(429, "{\"success\":false}").outcome());
        assertEquals(EmailDeliveryResult.Outcome.RETRYABLE_FAILURE, deliverWith(503, "{\"success\":false}").outcome());
    }

    @Test
    void classifiesPermanentAndMalformedResponsesSafely() throws Exception {
        assertEquals(EmailDeliveryResult.Outcome.PERMANENT_FAILURE, deliverWith(400, "{\"success\":false}").outcome());
        assertEquals(EmailDeliveryResult.Outcome.PERMANENT_FAILURE, deliverWith(200, "not-json").outcome());
    }

    @Test
    void storesProviderMessageIdFromValidatedContract() throws Exception {
        EmailDeliveryResult result = deliverWith(200, "{\"success\":true,\"messageId\":\"provider-123\"}");
        assertEquals(EmailDeliveryResult.Outcome.ACCEPTED, result.outcome());
        assertEquals("provider-123", result.providerMessageId());
    }

    private EmailDeliveryResult deliverWith(int status, String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new EmailService(mailProperties(), objectMapper, client).deliver(
                "employee@example.com", "IN_APP_NOTIFICATION",
                Map.of("title", "Test", "message", "Safe", "link", "/tasks"), UUID.randomUUID());
    }

    private MailProperties mailProperties() {
        return new MailProperties(
                true,
                "GOOGLE_APPS_SCRIPT",
                "https://script.google.com/macros/s/test/exec",
                "test-secret",
                "Leaflet EMS",
                "https://leaflet-employee-frontend.vercel.app"
        );
    }
}
