# Phase 3 — Secure Upload and Attachment Architecture

## Scope confirmation

This phase changes only upload, attachment, media ownership, delivery, quota,
cleanup, provider-boundary, and related audit behavior. It does not change the
Phase 1 session model or the Phase 2 manager reporting-scope rules. Existing
authorization services are reused when media is attached or downloaded.

## Upload inventory

| Old flow | Old behavior and risk | Phase 3 replacement |
|---|---|---|
| General `/api/v1/uploads` endpoint | Any authenticated role could send one browser-described file. Images used the image resource type; every other type became unrestricted `raw` content. There was no purpose, canonical owner, parent binding, content decode, malware boundary, stored quota, or orphan cleanup. | Removed. `POST /api/v1/media/uploads` accepts exactly one enum purpose and one file. |
| Frontend helper | Tried the backend, then performed an unsigned browser-to-Cloudinary upload with a public preset. | Unsigned fallback and public Cloudinary environment variables removed. All uploads go through the authenticated backend. |
| Profile image | Employee uploaded via the general endpoint and sent a trusted `profilePhotoUrl` back to the profile API. Replacement did not delete the old provider asset. | `PROFILE_IMAGE`, verified public provider identity, self-owned media ID, self-profile-only binding, canonical URL resolved by the backend, old canonical asset deleted on replacement. |
| Project document | Create/update accepted an arbitrary URL. | Create/update accepts `documentMediaAssetId`; only a verified `PROJECT_ATTACHMENT` can bind to the authorized project. Legacy URLs are classified but are not returned as verified downloads. |
| Project-note attachments | Provider URLs were embedded inside client-authored JSON. Files could be images or broad raw documents, and the URL had no owner check. | Note DTO carries up to ten media IDs. A join table owns canonical relationships. Only JPEG, PNG, and PDF are allowed, and each ID is verified and bound after project-note authorization. |
| Task-comment attachment | Comment DTO accepted `attachmentUrl` and `attachmentName`. Project access was checked, but asset identity and ownership were not. | Comment DTO accepts one `TASK_ATTACHMENT` media ID. Ownership, purpose, state, and task/project access are checked. |
| Payment attachments | Browser supplied URL, name, and MIME. Replacement removed rows but did not delete provider objects. | Payment DTO accepts up to five `PAYMENT_ATTACHMENT` IDs. Only financial actors can reach the parent operation. Provider metadata is server-derived and removed files are deleted server-side. |
| HR documents | Administrator supplied a public/permanent URL. Employees received that URL directly. DB deletion did not remove the provider object. | Admin uploads `HR_DOCUMENT`, then binds its media ID to a staff document. It is private, scan-gated, and downloadable only through an authenticated, audited, short-lived redirect. Managers do not receive HR access. |
| Leave attachments | No product flow exists. | No purpose added. |
| Daily-log attachments | No product flow exists. | No purpose added. |
| Deletion and cleanup | Business-row deletion generally did not delete provider data; unattached uploads had no cleanup. | Owner/admin can delete an unattached asset by UUID. Parent replacement/deletion removes provider content and retains deleted metadata. A PostgreSQL `SKIP LOCKED` scheduled cleanup removes expired unattached assets. |

The browser never controls a provider public ID, folder, resource type, delivery
type, owner, trusted URL, upload preset, or transformation.

## Files created

- `src/main/resources/db/migration/V36__secure_media_assets.sql` — canonical media schema, business foreign keys, and legacy classifications.
- `src/main/java/com/ems/backend/config/MediaProperties.java` — quotas, private-link lifetime, and scanner configuration.
- `src/main/java/com/ems/backend/media/UploadPurpose.java` — purpose policies and role/target rules.
- `src/main/java/com/ems/backend/media/MediaAsset.java`
- `src/main/java/com/ems/backend/media/MediaAssetRepository.java`
- `src/main/java/com/ems/backend/media/MediaStatus.java`
- `src/main/java/com/ems/backend/media/ScanningStatus.java`
- `src/main/java/com/ems/backend/media/DetectedMedia.java`
- `src/main/java/com/ems/backend/media/MediaValidationException.java`
- `src/main/java/com/ems/backend/media/MediaContentInspector.java`
- `src/main/java/com/ems/backend/media/MalwareScanner.java`
- `src/main/java/com/ems/backend/media/ClamAvMalwareScanner.java`
- `src/main/java/com/ems/backend/media/CloudinaryGateway.java`
- `src/main/java/com/ems/backend/media/CloudinaryRestGateway.java`
- `src/main/java/com/ems/backend/media/MediaUploadService.java`
- `src/main/java/com/ems/backend/media/MediaAttachmentService.java`
- `src/main/java/com/ems/backend/media/MediaDeliveryService.java`
- `src/main/java/com/ems/backend/media/MediaCleanupService.java`
- `src/main/java/com/ems/backend/media/MediaController.java`
- `src/main/java/com/ems/backend/media/dto/MediaAssetResponse.java`
- `src/main/java/com/ems/backend/project/ProjectNoteMediaAttachment.java`
- `src/main/java/com/ems/backend/project/ProjectNoteMediaAttachmentRepository.java`
- `src/main/java/com/ems/backend/project/ProjectPaymentAttachmentRepository.java`
- Phase 3 tests under `src/test/java/com/ems/backend/media/`
- `src/test/java/com/ems/backend/migration/SecureMediaMigrationTest.java`

The old `upload/CloudinaryUploadService.java` and
`upload/FileUploadController.java` were removed.

## Files modified

- Backend environment/configuration: `.env.example`, `README.md`,
  `application.yml`, `CloudinaryProperties`, `ProductionEnvironmentValidator`,
  and its test.
- Error handling: `GlobalExceptionHandler` now returns controlled multipart,
  invalid-purpose, and 413 responses.
- Canonical media mappings: `User`, `StaffDocument`, `Project`,
  `ProjectNote`, `ProjectPaymentAttachment`, and `TaskComment`, plus their
  related repositories.
- Business DTOs and services for profile, staff documents, projects, notes,
  payments, and task comments now use media IDs rather than trusted URLs.
- `AuthService` and user response mapping no longer treat legacy profile URLs
  as verified images.
- Frontend upload-related files: `lib/api.ts`, profile, staff-document, project,
  and employee-project views, plus `.env.example`.
- Workspace `README.md` removes documentation for unsigned browser uploads.

## Database migration

V36 creates `media_assets` with UUID identity; owner and creator foreign keys;
explicit purpose, status, scan status, privacy, provider identity, detected
content metadata, checksum, lifecycle timestamps, optimistic versioning,
failure code, and attached-parent identity.

Constraints enforce positive sizes/dimensions, provider metadata only in valid
states, attachment metadata for `ATTACHED`, and deletion timestamps for
`DELETED`. Unique partial indexes protect provider asset/public identities.
Indexes support owner history, status/purpose lookup, and orphan cleanup.

Canonical foreign keys are added to profile images, HR documents, project
documents, payment attachments, and task comments. Project-note media uses a
separate ordered join table. No existing URL is converted into trusted media.
Existing data is marked `LEGACY_UNVERIFIED` or, for HR,
`LEGACY_PRIVATE_REVIEW_REQUIRED`. Legacy HR URLs are no longer returned by the
new document response.

Unattached HR media expires after four hours; other unattached purposes expire
after 24 hours. Cleanup uses row locks with `SKIP LOCKED`, is safe across
instances, deletes provider content server-side, then preserves a `DELETED`
metadata/audit record. Provider deletion failure is retained for retry.

V36 is forward-only. Database rollback requires restoring a pre-migration
backup; dropping columns/tables is not a safe application rollback.

## Upload policies

| Purpose | Formats | Maximum | Dimensions/pixels | Delivery | Upload/attach authorization | Target | Scan |
|---|---|---:|---|---|---|---|---|
| `PROFILE_IMAGE` | JPEG, PNG | 5 MiB | 128–4096 each axis; 16M pixels; one frame | Public verified Cloudinary `image/upload` | Any authenticated user; self-owned/self-profile only | User profile | Not required |
| `PROJECT_ATTACHMENT` | JPEG, PNG, PDF | 10 MiB | Images max 8192 each axis and 25M pixels; one frame | Private `image` or `raw`, authenticated delivery | Authenticated uploader plus existing project/note mutation policy | Project or project note | Required |
| `TASK_ATTACHMENT` | JPEG, PNG, PDF | 10 MiB | Images max 8192/25M; one frame | Private authenticated delivery | Authenticated uploader plus accessible task/project policy | Task comment | Required |
| `PAYMENT_ATTACHMENT` | JPEG, PNG, PDF | 10 MiB | Images max 8192/25M; one frame | Private authenticated delivery | Admin/manager upload plus financial parent policy | Project payment | Required |
| `HR_DOCUMENT` | PDF | 10 MiB | Not applicable | Private `raw/authenticated` | Administrator upload/manage; employee self/admin view only | Staff document | Required |

SVG, HTML, executables, archives, macros, Office documents, GIF, WebP, unknown
binary, and arbitrary raw content are rejected.

## Security architecture

- Spring limits multipart files to 10 MiB and requests to 11 MiB with disk
  spooling. Purpose limits are rechecked during bounded streaming.
- Temporary files use random names, protected permissions where supported, no
  user path, no execution, and cleanup on success and every failure path.
- Magic bytes, extension/MIME consistency, strict JPEG/PNG endings, full image
  decode, dimensions, pixels, frames, PDF EOF/xref structure, active PDF
  tokens, checksum, and trailing data are validated. Decode concurrency is
  capped at two.
- Required document scans fail closed. Clean content can proceed; malware is
  rejected; scanner failure/unavailability produces `QUARANTINED` without a
  usable provider object or business attachment.
- Cloudinary uses backend-only signed API requests, opaque UUID public IDs, no
  overwrite, no filename-derived identity, and purpose-controlled folders and
  delivery types. Response asset ID, public ID, type, format, size,
  dimensions, creation time, HTTPS host/path, and overwrite flags are checked.
- Database-backed user/IP/purpose rolling limits are combined with a locked
  user row, pending-count cap, and stored-byte quota, so concurrent requests by
  one user cannot bypass quota checks.
- Business attachment binding locks the asset row and verifies owner, purpose,
  `VERIFIED` state, target type, and compatible prior attachment.
- Private delivery reloads the parent record and invokes the existing HR,
  project, or financial authorization policy before issuing a two-minute
  signed URL. Responses are no-store, private, attachment-disposition, and
  `nosniff`; access is audited without logging the signed URL.
- Replacement/deletion uses canonical media UUIDs only and provider deletion is
  backend-only. Task/project parent deletion cleans attached provider objects.

## API changes

- Added `POST /api/v1/media/uploads`.
- Added `DELETE /api/v1/media/assets/{assetId}` for authorized unattached media.
- Added `GET /api/v1/media/assets/{assetId}/download` for authenticated private
  delivery.
- Removed `/api/v1/uploads`.
- Profile, project-document, project-note, task-comment, payment-attachment,
  and HR-document requests use media UUIDs.
- Private responses expose an application media ID/download action, never a
  permanent HR provider URL.
- Upload responses expose safe detected metadata and status. A private
  provider URL is never returned.
- Invalid shape/content returns 400, forbidden purpose/parent access 403,
  missing asset 404, invalid state 409, request/quota size 413, rolling limit
  429, and temporary scanner/provider failure a controlled status/message.

## Tests

Latest local result:

- Maven: 102 discovered, 81 passed, 0 failed, 0 errors, 21 skipped.
- New non-database Phase 3 tests passed for content inspection, multipart
  shape, cross-owner/wrong-purpose/state binding, provider metadata rejection,
  and private HR delivery authorization/audit invocation.
- The 21 skipped tests require a real PostgreSQL/Testcontainers environment.
  Docker was unavailable and local PostgreSQL did not answer on port 5432.
- `SecureMediaMigrationTest` is present and rehearses V1–V35 data followed by
  V36, legacy classification, indexes, and the absence of fabricated verified
  media. It was skipped locally for the reason above.
- Provider HTTP timeout/429/5xx behavior is translated in the gateway, but no
  live Cloudinary call was made and provider credentials were not used.
- Frontend production build, lint, TypeScript checking, and all 18 route
  prerenders passed.

## Breaking changes

- Unsigned browser upload and the old general upload endpoint are removed.
- URL-only attachment/profile/project inputs are rejected by the new DTO
  contracts.
- Supported files are deliberately reduced to JPEG, PNG, and PDF, with a 5 MiB
  profile limit and 10 MiB attachment limit.
- New business records require a verified media UUID.
- HR files are no longer permanent/public links.
- Required-scan uploads may remain quarantined and cannot be attached.
- Existing legacy URLs are preserved for review but are not upgraded to
  verified media or returned as canonical private downloads.

## Environment variables

Changed or added, without values:

- `CLOUDINARY_CLOUD_NAME`
- `CLOUDINARY_API_KEY`
- `CLOUDINARY_API_SECRET`
- `MEDIA_USER_HOURLY_LIMIT`
- `MEDIA_IP_HOURLY_LIMIT`
- `MEDIA_PENDING_LIMIT`
- `MEDIA_STORED_BYTES_PER_PURPOSE`
- `MEDIA_PRIVATE_DOWNLOAD_TTL_SECONDS`
- `MEDIA_CLEANUP_CRON`
- `MEDIA_SCANNER_ENABLED`
- `MEDIA_SCANNER_HOST`
- `MEDIA_SCANNER_PORT`
- `MEDIA_SCANNER_CONNECT_TIMEOUT_MS`
- `MEDIA_SCANNER_READ_TIMEOUT_MS`

`CLOUDINARY_UPLOAD_PRESET`, `NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME`, and
`NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET` are no longer used.

## Manual deployment procedure

1. Take and verify a restorable Supabase PostgreSQL backup.
2. Export an inventory of every legacy URL field, especially HR raw/public
   resources; do not download or auto-trust external URLs.
3. Configure backend Cloudinary API credentials, disable/remove the unsigned
   preset, and configure a private reachable ClamAV service.
4. Run the full PostgreSQL migration/integration suite against a disposable
   production-compatible database, then deploy V36.
5. Roll out the backend and confirm production startup validation accepts the
   scanner/provider configuration.
6. Smoke-test valid profile JPEG/PNG plus valid and rejected/quarantined
   project, task, payment, and HR files.
7. Verify an employee can download only their own HR file, an admin can access
   it, and a manager/unrelated employee cannot; inspect headers and audit rows.
8. Roll out the frontend only after the backend contract is live.
9. Monitor upload rejection, quarantine, provider failure, download denial,
   rate-limit, quota, and orphan-cleanup audit events.
10. Review/replace legacy assets manually. Delete unsafe provider objects only
    after business-owner confirmation and backup.
11. For rollback, revert frontend first and keep the backend capable of reading
    V36. Do not down-migrate V36. Restore the backup only for a severe
    migration/data incident, and reconcile provider objects created after the
    backup separately.

## Remaining risks

- ClamAV integration is implemented but was not exercised against a live
  scanner here. This work does **not** claim malware-safe document handling.
  Production document uploads fail closed into quarantine when scanning is not
  clean.
- Live Cloudinary signed upload/delete/download behavior still requires staging
  smoke tests with the real account configuration.
- PostgreSQL/Testcontainers migration and real Spring Security integration
  tests were skipped locally because no PostgreSQL runtime was available.
- Provider deletion and the enclosing database transaction cannot be atomic.
  A database failure after successful provider deletion requires operational
  reconciliation; an outbox/retry workflow is deferred.
- Broader leave/attendance correctness, approval concurrency, email outbox,
  general performance optimization, large-service/component refactoring,
  HttpOnly refresh-token architecture, and infrastructure proxy/WAF hardening
  remain outside Phase 3.
- Database audit/rate-limit tables can grow and need operational retention and
  monitoring.
