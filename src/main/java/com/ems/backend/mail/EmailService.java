package com.ems.backend.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ems.backend.config.MailProperties;
import com.ems.backend.notification.NotificationType;
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

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
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
            log.warn("Password OTP delivery failed for {}", toEmail);
        }
        return sent;
    }

    public boolean sendTemporaryPassword(String toEmail, String fullName, String temporaryPassword) {
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
            log.warn("Temporary password delivery failed for {}", toEmail);
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

    private boolean send(String toEmail, String subject, String body, String htmlBody) {
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
            String payload = buildWebhookPayload(toEmail, subject, body, htmlBody);
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

    String buildWebhookPayload(String toEmail, String subject, String body, String htmlBody) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "secret", mailProperties.googleWebhookSecret(),
                "to", toEmail,
                "subject", subject,
                "body", body,
                "htmlBody", htmlBody,
                "fromName", mailProperties.resolvedFromName()
        ));
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
