CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_asset_id VARCHAR(255),
    provider_public_id VARCHAR(255),
    resource_type VARCHAR(20) NOT NULL,
    delivery_type VARCHAR(30) NOT NULL,
    provider_secure_url VARCHAR(1000),
    original_filename VARCHAR(255),
    detected_mime_type VARCHAR(100) NOT NULL,
    detected_format VARCHAR(20) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    width INTEGER,
    height INTEGER,
    frame_count INTEGER,
    private_asset BOOLEAN NOT NULL,
    scanning_status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMP,
    rejected_at TIMESTAMP,
    attached_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    failure_reason_code VARCHAR(80),
    attached_resource_type VARCHAR(40),
    attached_resource_id VARCHAR(100),
    CONSTRAINT chk_media_size_positive CHECK (size_bytes > 0),
    CONSTRAINT chk_media_version_nonnegative CHECK (version >= 0),
    CONSTRAINT chk_media_dimensions CHECK (
        (width IS NULL AND height IS NULL)
        OR (width > 0 AND height > 0)
    ),
    CONSTRAINT chk_media_frame_count CHECK (frame_count IS NULL OR frame_count > 0),
    CONSTRAINT chk_media_provider_identity CHECK (
        (status IN ('PENDING', 'QUARANTINED', 'REJECTED')
            AND provider_asset_id IS NULL
            AND provider_public_id IS NULL
            AND provider_secure_url IS NULL)
        OR
        (status IN ('VERIFIED', 'ATTACHED')
            AND provider_asset_id IS NOT NULL
            AND provider_public_id IS NOT NULL
            AND provider_secure_url IS NOT NULL)
        OR status = 'DELETED'
    ),
    CONSTRAINT chk_media_attachment_state CHECK (
        (status = 'ATTACHED'
            AND attached_at IS NOT NULL
            AND attached_resource_type IS NOT NULL
            AND attached_resource_id IS NOT NULL)
        OR
        (status <> 'ATTACHED')
    ),
    CONSTRAINT chk_media_deleted_state CHECK (
        (status = 'DELETED' AND deleted_at IS NOT NULL)
        OR status <> 'DELETED'
    )
);

CREATE UNIQUE INDEX uq_media_provider_asset
    ON media_assets(provider_asset_id)
    WHERE provider_asset_id IS NOT NULL;
CREATE UNIQUE INDEX uq_media_provider_public_identity
    ON media_assets(resource_type, delivery_type, provider_public_id)
    WHERE provider_public_id IS NOT NULL;
CREATE INDEX idx_media_owner_created ON media_assets(owner_user_id, created_at DESC);
CREATE INDEX idx_media_purpose_status_created ON media_assets(purpose, status, created_at DESC);
CREATE INDEX idx_media_unattached_cleanup
    ON media_assets(created_at)
    WHERE status IN ('PENDING', 'QUARANTINED', 'VERIFIED');

ALTER TABLE users
    ADD COLUMN profile_media_asset_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    ADD COLUMN profile_photo_legacy_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE users
SET profile_photo_legacy_status = 'LEGACY_UNVERIFIED'
WHERE profile_photo_url IS NOT NULL AND btrim(profile_photo_url) <> '';
CREATE UNIQUE INDEX uq_users_profile_media_asset
    ON users(profile_media_asset_id)
    WHERE profile_media_asset_id IS NOT NULL;

ALTER TABLE staff_documents
    ADD COLUMN media_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD COLUMN legacy_asset_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE staff_documents SET legacy_asset_status = 'LEGACY_PRIVATE_REVIEW_REQUIRED';
ALTER TABLE staff_documents ALTER COLUMN file_url DROP NOT NULL;
CREATE UNIQUE INDEX uq_staff_documents_media_asset
    ON staff_documents(media_asset_id)
    WHERE media_asset_id IS NOT NULL;

ALTER TABLE project_payment_attachments
    ADD COLUMN media_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD COLUMN legacy_asset_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE project_payment_attachments SET legacy_asset_status = 'LEGACY_UNVERIFIED';
ALTER TABLE project_payment_attachments ALTER COLUMN file_url DROP NOT NULL;
CREATE UNIQUE INDEX uq_payment_attachments_media_asset
    ON project_payment_attachments(media_asset_id)
    WHERE media_asset_id IS NOT NULL;

ALTER TABLE task_comments
    ADD COLUMN media_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    ADD COLUMN legacy_asset_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE task_comments
SET legacy_asset_status = 'LEGACY_UNVERIFIED'
WHERE attachment_url IS NOT NULL AND btrim(attachment_url) <> '';
CREATE UNIQUE INDEX uq_task_comments_media_asset
    ON task_comments(media_asset_id)
    WHERE media_asset_id IS NOT NULL;

ALTER TABLE projects
    ADD COLUMN document_media_asset_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    ADD COLUMN document_legacy_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE projects
SET document_legacy_status = 'LEGACY_UNVERIFIED'
WHERE document_url IS NOT NULL AND btrim(document_url) <> '';
CREATE UNIQUE INDEX uq_projects_document_media_asset
    ON projects(document_media_asset_id)
    WHERE document_media_asset_id IS NOT NULL;

ALTER TABLE project_notes
    ADD COLUMN legacy_attachment_status VARCHAR(30) NOT NULL DEFAULT 'NONE';
UPDATE project_notes
SET legacy_attachment_status = 'LEGACY_UNVERIFIED'
WHERE content LIKE '%"attachments"%';

CREATE TABLE project_note_media_attachments (
    id BIGSERIAL PRIMARY KEY,
    note_id BIGINT NOT NULL REFERENCES project_notes(id) ON DELETE CASCADE,
    media_asset_id UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_note_media_asset UNIQUE(media_asset_id),
    CONSTRAINT uq_project_note_media_order UNIQUE(note_id, display_order),
    CONSTRAINT chk_project_note_media_order CHECK (display_order >= 0)
);
CREATE INDEX idx_project_note_media_note
    ON project_note_media_attachments(note_id, display_order);
