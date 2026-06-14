package com.ems.backend.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ems.backend.config.MailProperties;
import com.ems.backend.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmailService(MailProperties mailProperties, ObjectMapper objectMapper) {
        this(
                mailProperties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build()
        );
    }

    EmailService(MailProperties mailProperties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.mailProperties = mailProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public boolean sendVerificationEmail(String toEmail, String fullName, String token) {
        String link = mailProperties.frontendBaseUrl() + "/verify-email?token=" + token;
        String subject = "Verify your Leaflet EMS email";
        String body = """
                Hello %s,

                Please verify your email address for Leaflet Employee Management System.

                Click the link below to verify:
                %s

                This link expires in 24 hours.

                If you did not create this account, you can ignore this email.
                """.formatted(fullName, link);

        boolean sent = send(toEmail, subject, body);
        if (!sent) {
            log.warn("Email verification delivery failed for {}", toEmail);
        }
        return sent;
    }

    public boolean sendPasswordOtp(String toEmail, String fullName, String otp, boolean accountSetup) {
        String link = mailProperties.frontendBaseUrl()
                + "/reset-password?email="
                + java.net.URLEncoder.encode(toEmail, java.nio.charset.StandardCharsets.UTF_8)
                + (accountSetup ? "&mode=setup" : "&mode=forgot");
        String subject = accountSetup
                ? "Set up your Leaflet EMS password"
                : "Reset your Leaflet EMS password";
        String action = accountSetup ? "finish setting up your account" : "reset your password";
        String body = """
                Hello %s,

                Use this one-time code to %s:

                %s

                Enter the code here:
                %s

                This code expires in 10 minutes. Do not share it with anyone.

                If you did not request this, you can ignore this email.
                """.formatted(fullName, action, otp, link);

        boolean sent = send(toEmail, subject, body);
        if (!sent) {
            log.warn("Password OTP delivery failed for {}", toEmail);
        }
        return sent;
    }

    public boolean sendEmailChangeOtp(String toEmail, String fullName, String otp) {
        String subject = "Confirm your new Leaflet EMS email";
        String body = """
                Hello %s,

                Use this one-time code to confirm this address as your new Leaflet EMS email:

                %s

                This code expires in 10 minutes. Do not share it with anyone.

                If you did not request this change, you can ignore this email.
                """.formatted(fullName, otp);

        boolean sent = send(toEmail, subject, body);
        if (!sent) {
            log.warn("Email change OTP delivery failed for {}", toEmail);
        }
        return sent;
    }

    public void sendNotificationEmail(String toEmail, NotificationType type, String title, String message, String link) {
        String subject = "[Leaflet EMS] " + title;
        String body = """
                %s

                %s

                Open in app: %s%s
                """.formatted(title, message, mailProperties.frontendBaseUrl(), link != null ? link : "");
        send(toEmail, subject, body);
    }

    private boolean send(String toEmail, String subject, String body) {
        if (!mailProperties.enabled()) {
            log.debug("Mail disabled - skipped sending '{}' to {}", subject, toEmail);
            return false;
        }

        if (!mailProperties.usesGoogleAppsScript()) {
            log.error("Unsupported mail provider '{}'", mailProperties.provider());
            return false;
        }

        if (isBlank(mailProperties.googleWebhookUrl()) || isBlank(mailProperties.googleWebhookSecret())) {
            log.error("Google Apps Script mail is enabled but its webhook URL or secret is missing");
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "secret", mailProperties.googleWebhookSecret(),
                    "to", toEmail,
                    "subject", subject,
                    "body", body,
                    "fromName", mailProperties.resolvedFromName()
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(mailProperties.googleWebhookUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = objectMapper.readTree(response.body());

            if (response.statusCode() < 200
                    || response.statusCode() >= 300
                    || !responseBody.path("success").asBoolean(false)) {
                log.error("Mail webhook rejected '{}' to {} with HTTP {}", subject, toEmail, response.statusCode());
                return false;
            }

            log.info("Sent email '{}' to {}", subject, toEmail);
            return true;
        } catch (Exception ex) {
            log.error("Failed to send email '{}' to {}: {}", subject, toEmail, ex.getMessage());
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
