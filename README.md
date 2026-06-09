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

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Environment

Use `.env.example` as the template for local configuration. Do not commit `.env` or production secrets.

## Tests

```bash
mvn test
```
