# Phase 7 — Pagination, API Capacity and Query Performance

## Authenticated API budgets

The application applies lightweight, per-backend-instance fixed-window limits after JWT authentication. These counters do not add a database query to ordinary API requests.

| Category | Default | Examples |
|---|---:|---|
| Read | 120/user/minute | tasks, projects, leave, notifications |
| Write | 30/user/minute | create, update, delete, attendance mutations |
| Expensive read | 30/user/minute | daily attendance summary |
| Export | 10/user/minute | CSV exports |

Responses include `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`. Rejections include `Retry-After` and use the standard `RATE_LIMITED` JSON response.

Environment overrides:

- `API_RATE_LIMIT_ENABLED`
- `API_READ_REQUESTS_PER_MINUTE`
- `API_WRITE_REQUESTS_PER_MINUTE`
- `API_EXPENSIVE_READ_REQUESTS_PER_MINUTE`
- `API_EXPORT_REQUESTS_PER_MINUTE`

These limits protect one application instance. If the backend is scaled horizontally, enforce the same aggregate policy at the edge or move counters to Redis. Database-backed login and OTP limits remain durable across restarts and instances.

## Attendance summary optimization

Attendance thresholds are loaded once per summary request and cached for 30 seconds. Administrative updates invalidate the local cache after transaction completion. This avoids repeated settings queries for every visible employee while permitting settings changed on another instance to converge within 30 seconds.

## Frontend request fan-out

The dashboard starts its independent projects, tasks, users, attendance, logs, and leave requests together. This preserves the existing API contracts while removing the previous two-stage network waterfall.

Dashboard list responses are reused for 15 seconds during client-side navigation. In-flight GETs are deduplicated, cache entries are isolated by the current access token, and every successful mutation invalidates the cache. Requests with an `AbortSignal` bypass sharing so one component cannot cancel another component's request.

An August 2026 local smoke test against the configured remote Supabase database measured the six dashboard reads at approximately 2.91 seconds in total when run sequentially, while the slowest individual request was approximately 1.08 seconds. A controller that merely called the same six services sequentially was therefore rejected; the independent requests remain parallel until a purpose-built aggregate database query can be benchmarked against them.

## Authentication query reduction

JWT validation caches a minimal projection containing only user ID, email, role, active status, and security version for 15 seconds. Concurrent cache misses for the same process are collapsed. Passwords and mutable JPA entities are never cached. Successful security audit events evict the affected user after transaction commit, and repeated `SecurityUtils.getCurrentUser()` calls reuse one user lookup within the same HTTP request.

The cache is process-local. On more than one backend instance, configure a shared cache/invalidation channel or treat `AUTHENTICATION_CACHE_TTL_SECONDS` as the maximum cross-instance revocation delay.

## Connection pool and metrics

The Hikari pool defaults to 8 maximum connections and 3 minimum idle connections per backend instance. Override `DB_MAX_POOL_SIZE`, `DB_MIN_IDLE`, and `DB_CONNECTION_TIMEOUT_MS` based on the Supabase connection budget and the number of Render instances; the total possible database connections is the maximum pool size multiplied by instance count.

Spring Boot health is public at `/actuator/health`. `/actuator/metrics` and `/actuator/prometheus` require an administrator JWT and expose HTTP, JVM, process, and Hikari measurements for production diagnosis.

## Local verification baseline

With the testing dataset and remote Supabase connection, the daily attendance endpoint improved from approximately 0.69 to 1.80 requests/second sequentially and from 3.24 to 6.17 requests/second at concurrency 10. Results are dataset- and environment-specific and are not a production capacity guarantee.
