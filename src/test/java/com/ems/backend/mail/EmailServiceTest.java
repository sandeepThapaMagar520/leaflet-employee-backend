package com.ems.backend.mail;

import com.ems.backend.config.MailProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

        assertTrue(emailService.sendEmailChangeOtp("employee@example.com", "Employee", "123456"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void webhookPayloadIncludesHtmlAndPlainTextFallback() throws Exception {
        EmailService emailService = new EmailService(mailProperties(), objectMapper, mock(HttpClient.class));

        JsonNode payload = objectMapper.readTree(emailService.buildWebhookPayload(
                "employee@example.com",
                "Subject",
                "Plain text",
                "<strong>Formatted</strong>"
        ));

        assertTrue(payload.path("body").asText().contains("Plain text"));
        assertTrue(payload.path("htmlBody").asText().contains("<strong>Formatted</strong>"));
        assertTrue(payload.path("fromName").asText().contains("Leaflet EMS"));
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
