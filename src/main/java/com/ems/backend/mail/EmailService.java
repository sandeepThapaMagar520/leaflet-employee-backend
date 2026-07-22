package com.ems.backend.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ems.backend.config.MailProperties;
import com.ems.backend.notification.NotificationType;
import com.ems.backend.outbox.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeout;

    @Autowired
    public EmailService(MailProperties mailProperties, ObjectMapper objectMapper, OutboxProperties outboxProperties) {
        this(
                mailProperties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(outboxProperties.providerConnectTimeout())
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build(),
                outboxProperties.providerReadTimeout()
        );
    }

    EmailService(MailProperties mailProperties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(mailProperties, objectMapper, httpClient, Duration.ofSeconds(20));
    }

    EmailService(MailProperties mailProperties, ObjectMapper objectMapper, HttpClient httpClient, Duration readTimeout) {
        this.mailProperties = mailProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
    }

    boolean sendVerificationEmail(String toEmail, String fullName, String token) {
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
        String htmlBody = emailLayout(
                "Verify your email",
                "Hello " + escapeHtml(fullName) + ",",
                """
                <p>Please confirm your email address to finish securing your Leaflet EMS account.</p>
                %s
                <p style="margin:24px 0 0;color:#64748b;font-size:13px;">This verification link expires in 24 hours.</p>
                """.formatted(actionButton("Verify email address", link)),
                "If you did not create this account, you can safely ignore this email."
        );

        boolean sent = send(toEmail, subject, body, htmlBody);
        if (!sent) {
            log.warn("Legacy direct email verification delivery failed");
        }
        return sent;
    }

    boolean sendPasswordOtp(String toEmail, String fullName, String otp, boolean accountSetup) {
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
        String htmlBody = emailLayout(
                accountSetup ? "Verify your account setup" : "Reset your password",
                "Hello " + escapeHtml(fullName) + ",",
                """
                <p>Use the verification code below to %s.</p>
                %s
                %s
                <p style="margin:24px 0 0;color:#64748b;font-size:13px;">This code expires in 10 minutes and can only be used once.</p>
                """.formatted(
                        escapeHtml(action),
                        codeBlock(otp, "One-time verification code"),
                        actionButton(accountSetup ? "Continue account setup" : "Continue password reset", link)
                ),
                "Do not share this code with anyone. Leaflet EMS staff will never ask you for it."
        );

        boolean sent = send(toEmail, subject, body, htmlBody);
        if (!sent) {
            log.warn("Legacy direct password OTP delivery failed");
        }
        return sent;
    }

    boolean sendTemporaryPassword(String toEmail, String fullName, String temporaryPassword) {
        String link = mailProperties.frontendBaseUrl()
                + "/reset-password?email="
                + java.net.URLEncoder.encode(toEmail, java.nio.charset.StandardCharsets.UTF_8)
                + "&mode=setup";
        String subject = "Start your Leaflet EMS account setup";
        String body = """
                Hello %s,

                An administrator created your Leaflet Employee Management System account.

                Your temporary password is:

                %s

                Start your account setup here:
                %s

                Enter your email and temporary password. We will then email you a six-digit verification code
                before you can create your permanent password.

                If you were not expecting this account, contact your administrator.
                """.formatted(fullName, temporaryPassword, link);
        String htmlBody = emailLayout(
                "Your Leaflet EMS account is ready",
                "Hello " + escapeHtml(fullName) + ",",
                """
                <p>An administrator created your employee account. Use the temporary one-time password below to begin setup.</p>
                %s
                %s
                <p style="margin:24px 0 0;color:#64748b;font-size:13px;">After confirming this password, we will email you a six-digit OTP before you create your permanent password.</p>
                """.formatted(
                        codeBlock(temporaryPassword, "Temporary one-time password"),
                        actionButton("Start account setup", link)
                ),
                "If you were not expecting this account, contact your administrator before continuing."
        );

        boolean sent = send(toEmail, subject, body, htmlBody);
        if (!sent) {
            log.warn("Legacy direct account setup delivery failed");
        }
        return sent;
    }

    boolean sendEmailChangeOtp(String toEmail, String fullName, String otp) {
        String subject = "Confirm your new Leaflet EMS email";
        String body = """
                Hello %s,

                Use this one-time code to confirm this address as your new Leaflet EMS email:

                %s

                This code expires in 10 minutes. Do not share it with anyone.

                If you did not request this change, you can ignore this email.
                """.formatted(fullName, otp);
        String htmlBody = emailLayout(
                "Confirm your new email",
                "Hello " + escapeHtml(fullName) + ",",
                """
                <p>Use this code to confirm this address as your new Leaflet EMS email.</p>
                %s
                <p style="margin:24px 0 0;color:#64748b;font-size:13px;">This code expires in 10 minutes and can only be used once.</p>
                """.formatted(codeBlock(otp, "Email verification code")),
                "If you did not request this email change, ignore this message and keep your current address."
        );

        boolean sent = send(toEmail, subject, body, htmlBody);
        if (!sent) {
            log.warn("Legacy direct email change delivery failed");
        }
        return sent;
    }

    void sendNotificationEmail(String toEmail, NotificationType type, String title, String message, String link) {
        String subject = "[Leaflet EMS] " + title;
        String body = """
                %s

                %s

                Open in app: %s%s
                """.formatted(title, message, mailProperties.frontendBaseUrl(), link != null ? link : "");
        String appLink = mailProperties.frontendBaseUrl() + (link != null ? link : "");
        String htmlBody = emailLayout(
                title,
                "Leaflet EMS notification",
                """
                <p>%s</p>
                %s
                """.formatted(escapeHtml(message), actionButton("Open in Leaflet EMS", appLink)),
                "You received this email because this notification type is enabled in your profile settings."
        );
        send(toEmail, subject, body, htmlBody);
    }

    /** Provider boundary used only by the durable outbox worker. */
    public EmailDeliveryResult deliver(
            String toEmail,
            String templateKey,
            Map<String, Object> values,
            UUID outboxMessageId
    ) {
        String fullName = text(values, "fullName", "there");
        String subject;
        String body;
        switch (templateKey) {
            case "ACCOUNT_SETUP" -> {
                subject = "Start your Leaflet EMS account setup";
                body = "Hello %s,\n\nYour temporary password is:\n\n%s\n\nStart setup: %s/reset-password?email=%s&mode=setup\n\nThis credential is time limited."
                        .formatted(fullName, text(values, "temporaryPassword", ""), mailProperties.frontendBaseUrl(),
                                java.net.URLEncoder.encode(toEmail, java.nio.charset.StandardCharsets.UTF_8));
            }
            case "ACCOUNT_SETUP_OTP", "PASSWORD_RECOVERY_OTP" -> {
                boolean setup = templateKey.equals("ACCOUNT_SETUP_OTP");
                subject = setup ? "Set up your Leaflet EMS password" : "Reset your Leaflet EMS password";
                body = "Hello %s,\n\nYour one-time code is: %s\n\nThis code expires shortly and can only be used once."
                        .formatted(fullName, text(values, "otp", ""));
            }
            case "EMAIL_CHANGE_OTP" -> {
                subject = "Confirm your new Leaflet EMS email";
                body = "Hello %s,\n\nYour one-time email verification code is: %s\n\nThis code expires shortly."
                        .formatted(fullName, text(values, "otp", ""));
            }
            case "EMAIL_VERIFICATION" -> {
                subject = "Verify your Leaflet EMS email";
                body = "Hello %s,\n\nVerify your email: %s/verify-email?token=%s\n\nThis link expires shortly."
                        .formatted(fullName, mailProperties.frontendBaseUrl(), text(values, "token", ""));
            }
            case "IN_APP_NOTIFICATION" -> {
                subject = "[Leaflet EMS] " + text(values, "title", "Notification");
                body = "%s\n\n%s\n\nOpen in app: %s%s".formatted(
                        text(values, "title", "Notification"), text(values, "message", ""),
                        mailProperties.frontendBaseUrl(), text(values, "link", ""));
            }
            default -> { return EmailDeliveryResult.permanent("INVALID_TEMPLATE", "Unknown email template"); }
        }
        String html = emailLayout(subject, "Hello " + escapeHtml(fullName) + ",",
                "<p>" + escapeHtml(body).replace("\n", "<br>") + "</p>",
                "This is an automated Leaflet EMS message.");
        return sendStructured(toEmail, subject, body, html, outboxMessageId);
    }

    private boolean send(String toEmail, String subject, String body, String htmlBody) {
        return sendStructured(toEmail, subject, body, htmlBody, null).outcome()
                == EmailDeliveryResult.Outcome.ACCEPTED;
    }

    private EmailDeliveryResult sendStructured(
            String toEmail, String subject, String body, String htmlBody, UUID idempotencyKey
    ) {
        if (!mailProperties.enabled()) {
            return EmailDeliveryResult.permanent("MAIL_DISABLED", "Email delivery is disabled");
        }

        if (!mailProperties.usesGoogleAppsScript()) {
            log.error("Unsupported mail provider '{}'", mailProperties.provider());
            return EmailDeliveryResult.permanent("UNSUPPORTED_PROVIDER", "Unsupported mail provider");
        }

        if (isBlank(mailProperties.googleWebhookUrl()) || isBlank(mailProperties.googleWebhookSecret())) {
            log.error("Google Apps Script mail is enabled but its webhook URL or secret is missing");
            return EmailDeliveryResult.retryable("PROVIDER_CONFIGURATION", "Mail provider configuration is unavailable");
        }

        try {
            String payload = buildWebhookPayload(toEmail, subject, body, htmlBody, idempotencyKey);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(mailProperties.googleWebhookUrl()))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (idempotencyKey != null) requestBuilder.header("Idempotency-Key", idempotencyKey.toString());
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody;
            try {
                responseBody = objectMapper.readTree(response.body());
            } catch (Exception malformed) {
                return response.statusCode() >= 500
                        ? EmailDeliveryResult.retryable("MALFORMED_PROVIDER_RESPONSE", "Provider returned an invalid response")
                        : EmailDeliveryResult.permanent("MALFORMED_PROVIDER_RESPONSE", "Provider returned an invalid response");
            }

            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                return EmailDeliveryResult.retryable("PROVIDER_HTTP_" + response.statusCode(), "Provider temporarily rejected delivery");
            }
            if (responseBody.path("retryable").asBoolean(false)) {
                return EmailDeliveryResult.retryable("PROVIDER_TEMPORARY_REJECTION", "Provider temporarily rejected delivery");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !responseBody.path("success").asBoolean(false)) {
                return EmailDeliveryResult.permanent("PROVIDER_REJECTED", "Provider rejected delivery");
            }

            return EmailDeliveryResult.accepted(responseBody.path("messageId").isTextual()
                    ? responseBody.path("messageId").asText() : null);
        } catch (java.net.http.HttpTimeoutException exception) {
            return EmailDeliveryResult.retryable("PROVIDER_TIMEOUT", "Provider request timed out");
        } catch (java.io.IOException exception) {
            return EmailDeliveryResult.retryable("PROVIDER_NETWORK", "Provider network request failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return EmailDeliveryResult.retryable("DELIVERY_INTERRUPTED", "Delivery was interrupted");
        } catch (Exception ex) {
            return EmailDeliveryResult.permanent("DELIVERY_CONFIGURATION", "Delivery request could not be constructed");
        }
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    String buildWebhookPayload(String toEmail, String subject, String body, String htmlBody) throws Exception {
        return buildWebhookPayload(toEmail, subject, body, htmlBody, null);
    }

    String buildWebhookPayload(String toEmail, String subject, String body, String htmlBody, UUID idempotencyKey) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("secret", mailProperties.googleWebhookSecret());
        payload.put("to", toEmail);
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("htmlBody", htmlBody);
        payload.put("fromName", mailProperties.resolvedFromName());
        if (idempotencyKey != null) payload.put("idempotencyKey", idempotencyKey.toString());
        return objectMapper.writeValueAsString(payload);
    }

    private String emailLayout(String title, String greeting, String content, String footer) {
        return """
                <!doctype html>
                <html>
                <body style="margin:0;padding:0;background:#f1f5f9;font-family:Arial,sans-serif;color:#172033;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f1f5f9;padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#ffffff;border:1px solid #dbe3ec;border-radius:8px;overflow:hidden;">
                          <tr>
                            <td style="padding:22px 28px;background:#0f766e;color:#ffffff;">
                              <div style="font-size:19px;font-weight:700;">Leaflet EMS</div>
                              <div style="margin-top:4px;font-size:12px;color:#ccfbf1;">Employee Operations</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px 28px;">
                              <h1 style="margin:0 0 14px;font-size:24px;line-height:1.25;color:#0f172a;">%s</h1>
                              <p style="margin:0 0 22px;color:#475569;font-size:15px;">%s</p>
                              <div style="color:#334155;font-size:15px;line-height:1.65;">%s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:18px 28px;background:#f8fafc;border-top:1px solid #e2e8f0;color:#64748b;font-size:12px;line-height:1.55;">
                              %s
                              <div style="margin-top:8px;">This is an automated message from Leaflet EMS.</div>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                escapeHtml(title),
                escapeHtml(greeting),
                content,
                escapeHtml(footer)
        );
    }

    private String actionButton(String label, String link) {
        return """
                <div style="margin:26px 0 4px;">
                  <a href="%s" style="display:inline-block;padding:12px 18px;background:#0f766e;color:#ffffff;text-decoration:none;font-size:14px;font-weight:700;border-radius:6px;">%s</a>
                </div>
                """.formatted(escapeHtml(link), escapeHtml(label));
    }

    private String codeBlock(String value, String label) {
        return """
                <div style="margin:24px 0;padding:18px;background:#f8fafc;border:1px solid #cbd5e1;border-left:4px solid #0f766e;border-radius:6px;">
                  <div style="margin-bottom:8px;color:#64748b;font-size:12px;font-weight:700;text-transform:uppercase;">%s</div>
                  <div style="font-family:Consolas,monospace;font-size:26px;font-weight:700;letter-spacing:4px;color:#0f172a;">%s</div>
                </div>
                """.formatted(escapeHtml(label), escapeHtml(value));
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
