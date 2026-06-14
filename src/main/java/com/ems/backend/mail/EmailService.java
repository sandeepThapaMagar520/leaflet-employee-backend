package com.ems.backend.mail;

import com.ems.backend.config.MailProperties;
import com.ems.backend.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public EmailService(
            org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider,
            MailProperties mailProperties
    ) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailProperties = mailProperties;
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
            log.info("Email verification link for {}: {}", toEmail, link);
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
            log.info("Password OTP for {}: {} (setup link: {})", toEmail, otp, link);
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
            log.info("Email change OTP for {}: {}", toEmail, otp);
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
        if (!mailProperties.enabled() || mailSender == null) {
            log.debug("Mail disabled — skipped sending '{}' to {}", subject, toEmail);
            return false;
        }

        String fromAddress = mailProperties.resolvedFromAddress();
        if (fromAddress == null) {
            log.warn("Mail enabled but MAIL_FROM / MAIL_USERNAME is missing — skipped sending '{}' to {}", subject, toEmail);
            return false;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Sent email '{}' to {}", subject, toEmail);
            return true;
        } catch (Exception ex) {
            log.error("Failed to send email '{}' to {}: {}", subject, toEmail, ex.getMessage());
            return false;
        }
    }
}
