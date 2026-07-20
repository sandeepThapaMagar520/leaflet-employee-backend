# Leaflet Employee Backend

Spring Boot REST API for the Leaflet Employee and Leaflet Admin employee management system.

## Tech Stack

- Java 21
- Spring Boot 3
- Spring Security with JWT
- Spring Data JPA
- PostgreSQL
- Flyway
- Springdoc OpenAPI / Swagger

## Setup

```bash
cp .env.example .env
mvn spring-boot:run
```

The API runs at `http://localhost:8080`.

## API Docs

When the backend is running:

- Swagger UI: `http://localhost:8080/docs`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Render Deployment

Create a Render Web Service from this repository.

- Runtime: Docker
- Branch: `main`
- Dockerfile path: `./Dockerfile`
- Docker command: leave empty
- Health check path: `/api/v1/health`

Required environment variables:

```bash
APP_ENVIRONMENT=production
DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<supabase_db_password>
JWT_SECRET=<minimum_32_character_secret>
JWT_EXPIRATION_MS=900000
JWT_ISSUER=leaflet-ems-production
JWT_AUDIENCE=leaflet-ems-web-production
JWT_KEY_ID=<stable-signing-key-identifier>
ALLOWED_ORIGIN_PATTERNS=https://<your-frontend-domain>
CLOUDINARY_CLOUD_NAME=<your_cloud_name>
CLOUDINARY_API_KEY=<your_cloudinary_api_key>
CLOUDINARY_API_SECRET=<your_cloudinary_api_secret>
MEDIA_SCANNER_ENABLED=true
MEDIA_SCANNER_HOST=<private_clamav_host>
SHOW_SQL=false
```

Production startup fails closed when critical database, JWT, CORS, email, or
upload configuration is missing or unsafe. The seeded-account incident
response, V33 verification, JWT rotation, and one-time emergency administrator
procedure are documented in
[`docs/phase-0-production-containment.md`](docs/phase-0-production-containment.md).
The access-token, OTP, password-recovery, session-revocation, rollout, and
operator behavior introduced in Phase 1 is documented in
[`docs/phase-1-authentication-session-security.md`](docs/phase-1-authentication-session-security.md).

Access tokens are intentionally short-lived (15 minutes by default). Changing
a password, completing recovery, changing an email, disabling an account, or
revoking sessions invalidates previously issued tokens. The browser currently
stores the access token in `sessionStorage`; a refresh-cookie migration remains
a later staged change.

Google Apps Script email settings (verification, OTP, and notification emails):

```bash
MAIL_ENABLED=true
MAIL_PROVIDER=GOOGLE_APPS_SCRIPT
GOOGLE_MAIL_WEBHOOK_URL=https://script.google.com/macros/s/<deployment-id>/exec
GOOGLE_MAIL_WEBHOOK_SECRET=<shared-webhook-secret>
MAIL_FROM_NAME=Leaflet EMS
FRONTEND_BASE_URL=https://<your-frontend-domain>
```

Store the same secret in the Apps Script `WEBHOOK_SECRET` script property. The web app must execute as the
script owner and allow access to anyone. OTP values are never written to application logs.

Render provides the `PORT` environment variable automatically, so it does not need to be set manually.

## Environment

Use `.env.example` as the template for local configuration. Do not commit `.env` or production secrets.

## Tests

```bash
mvn test
```
