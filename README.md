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
DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
DB_USERNAME=postgres.<project-ref>
DB_PASSWORD=<supabase_db_password>
JWT_SECRET=<minimum_32_character_secret>
JWT_EXPIRATION_MS=86400000
ALLOWED_ORIGIN_PATTERNS=http://localhost:3000,https://<your-frontend-domain>
CLOUDINARY_CLOUD_NAME=<your_cloud_name>
CLOUDINARY_UPLOAD_PRESET=<your_unsigned_upload_preset>
SHOW_SQL=false
```

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
