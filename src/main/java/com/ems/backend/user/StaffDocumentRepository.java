package com.ems.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, Long> {
    List<StaffDocument> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<StaffDocument> findByMediaAssetId(UUID mediaAssetId);
}
