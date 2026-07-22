package com.ems.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProductionEnvironmentValidator implements EnvironmentPostProcessor {
    private static final Set<String> PRODUCTION_NAMES = Set.of("prod", "production");
    private static final Set<String> UNSAFE_SECRET_MARKERS = Set.of(
            "password",
            "postgres",
            "changeme",
            "replace_this",
            "replace-with",
            "replace_with",
            "your_",
            "example",
            "development",
            "local_only"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    void validate(ConfigurableEnvironment environment) {
        if (!isProduction(environment)) {
            return;
        }

        List<String> errors = new ArrayList<>();
        String databaseUrl = required(environment, "DB_URL", errors);
        required(environment, "DB_USERNAME", errors);
        String databasePassword = requiredSecret(environment, "DB_PASSWORD", 12, errors);
        String jwtSecret = requiredSecret(environment, "JWT_SECRET", 32, errors);
        String jwtIssuer = required(environment, "JWT_ISSUER", errors);
        String jwtAudience = required(environment, "JWT_AUDIENCE", errors);
        String jwtKeyId = required(environment, "JWT_KEY_ID", errors);
        String jwtExpiration = required(environment, "JWT_EXPIRATION_MS", errors);
        String corsOrigins = required(environment, "ALLOWED_ORIGIN_PATTERNS", errors);
        String mailEnabled = required(environment, "MAIL_ENABLED", errors);
        String mailProvider = required(environment, "MAIL_PROVIDER", errors);
        String webhookUrl = required(environment, "GOOGLE_MAIL_WEBHOOK_URL", errors);
        String webhookSecret = requiredSecret(environment, "GOOGLE_MAIL_WEBHOOK_SECRET", 24, errors);
        String frontendUrl = required(environment, "FRONTEND_BASE_URL", errors);
        String cloudName = required(environment, "CLOUDINARY_CLOUD_NAME", errors);
        String cloudinaryApiKey = requiredSecret(environment, "CLOUDINARY_API_KEY", 6, errors);
        String cloudinaryApiSecret = requiredSecret(
                environment, "CLOUDINARY_API_SECRET", 16, errors
        );
        String mediaScannerEnabled = required(environment, "MEDIA_SCANNER_ENABLED", errors);
        String mediaScannerHost = required(environment, "MEDIA_SCANNER_HOST", errors);
        String mediaScannerPort = required(environment, "MEDIA_SCANNER_PORT", errors);
        required(environment, "OUTBOX_WORKER_ENABLED", errors);
        String outboxEncryptionKey = requiredSecret(environment, "OUTBOX_ENCRYPTION_KEY", 32, errors);

        if (databaseUrl != null && !isRemotePostgresJdbcUrl(databaseUrl)) {
            errors.add("DB_URL must be a non-local PostgreSQL JDBC URL");
        }
        if (databasePassword != null && isObviouslyUnsafe(databasePassword)) {
            errors.add("DB_PASSWORD contains a known placeholder or unsafe value");
        }
        if (jwtSecret != null && isObviouslyUnsafe(jwtSecret)) {
            errors.add("JWT_SECRET contains a known placeholder or unsafe value");
        }
        if (jwtSecret != null && jwtSecret.chars().distinct().count() < 12) {
            errors.add("JWT_SECRET does not contain enough character diversity");
        }
        if (jwtIssuer != null && (jwtIssuer.contains(" ") || jwtIssuer.length() > 200)) {
            errors.add("JWT_ISSUER must be a stable identifier without spaces");
        }
        if (jwtAudience != null && (jwtAudience.contains(" ") || jwtAudience.length() > 200)) {
            errors.add("JWT_AUDIENCE must be a stable identifier without spaces");
        }
        if (jwtKeyId != null && !jwtKeyId.matches("[A-Za-z0-9._-]{3,80}")) {
            errors.add("JWT_KEY_ID must be a safe 3-80 character identifier");
        }
        if (jwtExpiration != null) {
            try {
                long milliseconds = Long.parseLong(jwtExpiration);
                if (milliseconds < 300_000 || milliseconds > 1_800_000) {
                    errors.add("JWT_EXPIRATION_MS must be between 300000 and 1800000");
                }
            } catch (NumberFormatException exception) {
                errors.add("JWT_EXPIRATION_MS must be an integer");
            }
        }
        validateCors(corsOrigins, errors);

        if (mailEnabled != null && !Boolean.parseBoolean(mailEnabled)) {
            errors.add("MAIL_ENABLED must be true in production");
        }
        if (mailProvider != null && !"GOOGLE_APPS_SCRIPT".equalsIgnoreCase(mailProvider.trim())) {
            errors.add("MAIL_PROVIDER must be GOOGLE_APPS_SCRIPT");
        }
        if (webhookUrl != null && !isSecureUrl(webhookUrl)) {
            errors.add("GOOGLE_MAIL_WEBHOOK_URL must use HTTPS");
        }
        if (webhookSecret != null && isObviouslyUnsafe(webhookSecret)) {
            errors.add("GOOGLE_MAIL_WEBHOOK_SECRET contains a known placeholder or unsafe value");
        }
        if (frontendUrl != null && !isSecureUrl(frontendUrl)) {
            errors.add("FRONTEND_BASE_URL must use HTTPS");
        }
        if (cloudName != null && isObviouslyUnsafe(cloudName)) {
            errors.add("CLOUDINARY_CLOUD_NAME contains a placeholder value");
        }
        if (cloudinaryApiKey != null && isObviouslyUnsafe(cloudinaryApiKey)) {
            errors.add("CLOUDINARY_API_KEY contains a placeholder value");
        }
        if (cloudinaryApiSecret != null && isObviouslyUnsafe(cloudinaryApiSecret)) {
            errors.add("CLOUDINARY_API_SECRET contains a placeholder value");
        }
        if (mediaScannerEnabled != null && !Boolean.parseBoolean(mediaScannerEnabled)) {
            errors.add("MEDIA_SCANNER_ENABLED must be true in production");
        }
        if (mediaScannerHost != null
                && (mediaScannerHost.isBlank()
                || "localhost".equalsIgnoreCase(mediaScannerHost)
                || "127.0.0.1".equals(mediaScannerHost)
                || mediaScannerHost.contains("://"))) {
            errors.add("MEDIA_SCANNER_HOST must be an explicit reachable host name");
        }
        if (mediaScannerPort != null) {
            try {
                int port = Integer.parseInt(mediaScannerPort);
                if (port < 1 || port > 65535) {
                    errors.add("MEDIA_SCANNER_PORT must be between 1 and 65535");
                }
            } catch (NumberFormatException exception) {
                errors.add("MEDIA_SCANNER_PORT must be an integer");
            }
        }
        if (outboxEncryptionKey != null && isObviouslyUnsafe(outboxEncryptionKey)) {
            errors.add("OUTBOX_ENCRYPTION_KEY contains a known placeholder or unsafe value");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration. Startup refused: " + String.join("; ", errors)
            );
        }
    }

    private boolean isProduction(ConfigurableEnvironment environment) {
        String appEnvironment = environment.getProperty(
                "APP_ENVIRONMENT",
                environment.getProperty("app.environment", "")
        );
        if (PRODUCTION_NAMES.contains(appEnvironment.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION_NAMES::contains);
    }

    private String required(ConfigurableEnvironment environment, String key, List<String> errors) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            errors.add(key + " is required");
            return null;
        }
        return value.trim();
    }

    private String requiredSecret(
            ConfigurableEnvironment environment,
            String key,
            int minimumLength,
            List<String> errors
    ) {
        String value = required(environment, key, errors);
        if (value != null && value.length() < minimumLength) {
            errors.add(key + " must be at least " + minimumLength + " characters");
        }
        return value;
    }

    private void validateCors(String value, List<String> errors) {
        if (value == null) {
            return;
        }
        List<String> origins = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        if (origins.isEmpty()) {
            errors.add("ALLOWED_ORIGIN_PATTERNS must contain at least one origin");
            return;
        }
        for (String origin : origins) {
            if (!isSecureUrl(origin)
                    || origin.contains("*")
                    || origin.toLowerCase(Locale.ROOT).contains("localhost")
                    || origin.contains("127.0.0.1")
                    || !isOrigin(origin)) {
                errors.add("ALLOWED_ORIGIN_PATTERNS must contain only explicit HTTPS production origins");
                return;
            }
        }
    }

    private boolean isSecureUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isOrigin(String value) {
        try {
            URI uri = new URI(value.trim());
            return (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath()))
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isRemotePostgresJdbcUrl(String value) {
        if (!value.startsWith("jdbc:")) {
            return false;
        }
        try {
            URI uri = new URI(value.substring("jdbc:".length()));
            return "postgresql".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && !isLocalUrl(value);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isLocalUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("localhost") || normalized.contains("127.0.0.1");
    }

    private boolean isObviouslyUnsafe(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return UNSAFE_SECRET_MARKERS.stream().anyMatch(normalized::contains);
    }
}
