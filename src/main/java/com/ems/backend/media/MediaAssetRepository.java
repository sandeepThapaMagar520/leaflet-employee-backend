package com.ems.backend.media;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset join fetch asset.owner where asset.id = :id")
    Optional<MediaAsset> findByIdForUpdate(UUID id);

    long countByOwnerIdAndStatusIn(Long ownerId, java.util.Collection<MediaStatus> statuses);

    @Query("""
            select coalesce(sum(asset.sizeBytes), 0) from MediaAsset asset
            where asset.owner.id = :ownerId and asset.purpose = :purpose
              and asset.status in :statuses
            """)
    long sumStoredBytes(
            Long ownerId,
            UploadPurpose purpose,
            java.util.Collection<MediaStatus> statuses
    );

    long countByStatusInAndCreatedAtBefore(
            java.util.Collection<MediaStatus> statuses,
            Instant cutoff
    );

    @Query(value = """
            SELECT * FROM media_assets
            WHERE status IN ('PENDING', 'QUARANTINED', 'VERIFIED')
              AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MediaAsset> findCleanupCandidates(Instant cutoff, int batchSize);
}
