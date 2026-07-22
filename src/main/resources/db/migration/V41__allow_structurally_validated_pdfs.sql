-- Structurally validated PDFs are explicitly distinguished from malware-scanned files.
-- Existing quarantined and legacy assets are intentionally left unchanged.
ALTER TABLE media_assets
    DROP CONSTRAINT chk_media_scanning_values;

ALTER TABLE media_assets
    ADD CONSTRAINT chk_media_scanning_values CHECK (
        scanning_status IN (
            'NOT_REQUIRED', 'PENDING', 'CLEAN', 'STRUCTURE_VALIDATED',
            'MALWARE_DETECTED', 'FAILED', 'UNAVAILABLE'
        )
    );
