package com.ems.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionEnvironmentValidatorTest {
    private final ProductionEnvironmentValidator validator = new ProductionEnvironmentValidator();

    @Test
    void productionFailsWhenCriticalConfigurationIsMissing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENVIRONMENT", "production");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> validator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("DB_URL is required"));
        assertTrue(exception.getMessage().contains("JWT_SECRET is required"));
        assertTrue(exception.getMessage().contains("ALLOWED_ORIGIN_PATTERNS is required"));
    }

    @Test
    void productionFailsForKnownFallbackValues() {
        MockEnvironment environment = validProductionEnvironment()
                .withProperty("DB_PASSWORD", "postgres")
                .withProperty("JWT_SECRET", "replace_this_with_minimum_32_characters_secret")
                .withProperty("ALLOWED_ORIGIN_PATTERNS", "http://localhost:3000")
                .withProperty("GOOGLE_MAIL_WEBHOOK_SECRET", "replace_with_a_long_random_secret")
                .withProperty("CLOUDINARY_CLOUD_NAME", "your_cloud_name");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> validator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("DB_PASSWORD"));
        assertTrue(exception.getMessage().contains("JWT_SECRET"));
        assertTrue(exception.getMessage().contains("ALLOWED_ORIGIN_PATTERNS"));
        assertTrue(exception.getMessage().contains("GOOGLE_MAIL_WEBHOOK_SECRET"));
        assertTrue(exception.getMessage().contains("CLOUDINARY_CLOUD_NAME"));
    }

    @Test
    void productionAcceptsExplicitStrongConfiguration() {
        assertDoesNotThrow(() -> validator.validate(validProductionEnvironment()));
    }

    @Test
    void productionRejectsWeakJwtAndInvalidTokenMetadata() {
        MockEnvironment environment = validProductionEnvironment()
                .withProperty("JWT_SECRET", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .withProperty("JWT_ISSUER", "issuer with spaces")
                .withProperty("JWT_AUDIENCE", "audience with spaces")
                .withProperty("JWT_KEY_ID", "*")
                .withProperty("JWT_EXPIRATION_MS", "86400000");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> validator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("JWT_SECRET"));
        assertTrue(exception.getMessage().contains("JWT_ISSUER"));
        assertTrue(exception.getMessage().contains("JWT_AUDIENCE"));
        assertTrue(exception.getMessage().contains("JWT_KEY_ID"));
        assertTrue(exception.getMessage().contains("JWT_EXPIRATION_MS"));
    }

    @Test
    void localAndTestProfilesMayUseExplicitDevelopmentValues() {
        MockEnvironment local = new MockEnvironment()
                .withProperty("APP_ENVIRONMENT", "development")
                .withProperty("JWT_SECRET", "local_only_explicit_development_jwt_secret_123456");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        test.setProperty("JWT_SECRET", "local_only_explicit_test_jwt_secret_123456789");

        assertDoesNotThrow(() -> validator.validate(local));
        assertDoesNotThrow(() -> validator.validate(test));
    }

    @Test
    void productionCannotBypassValidationThroughCanonicalPropertyName() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production");

        assertThrows(IllegalStateException.class, () -> validator.validate(environment));
    }

    private MockEnvironment validProductionEnvironment() {
        return new MockEnvironment()
                .withProperty("APP_ENVIRONMENT", "production")
                .withProperty("DB_URL", "jdbc:postgresql://db.company.invalid:5432/leaflet?sslmode=require")
                .withProperty("DB_USERNAME", "leaflet_app")
                .withProperty("DB_PASSWORD", "S7rong-Database-Secret!2026")
                .withProperty("JWT_SECRET", "U7x!pQ2#zL9@vN4$kR8%wT1&cM6*eH3-B5sD0aF")
                .withProperty("JWT_ISSUER", "leaflet-ems-production")
                .withProperty("JWT_AUDIENCE", "leaflet-ems-web")
                .withProperty("JWT_KEY_ID", "production-key-2026-01")
                .withProperty("JWT_EXPIRATION_MS", "900000")
                .withProperty("ALLOWED_ORIGIN_PATTERNS", "https://employees.company.invalid")
                .withProperty("MAIL_ENABLED", "true")
                .withProperty("MAIL_PROVIDER", "GOOGLE_APPS_SCRIPT")
                .withProperty(
                        "GOOGLE_MAIL_WEBHOOK_URL",
                        "https://script.google.com/macros/s/deployment-id/exec"
                )
                .withProperty("GOOGLE_MAIL_WEBHOOK_SECRET", "M4il-Webhook-Secret!2026-Leaflet")
                .withProperty("FRONTEND_BASE_URL", "https://employees.company.invalid")
                .withProperty("CLOUDINARY_CLOUD_NAME", "leaflet-production")
                .withProperty("CLOUDINARY_API_KEY", "123456789")
                .withProperty("CLOUDINARY_API_SECRET", "strong-cloudinary-api-secret")
                .withProperty("MEDIA_SCANNER_ENABLED", "true")
                .withProperty("MEDIA_SCANNER_HOST", "clamav.internal")
                .withProperty("MEDIA_SCANNER_PORT", "3310");
    }
}
