package com.ems.backend.project;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface ProjectPaymentAttachmentRepository extends JpaRepository<ProjectPaymentAttachment, Long> {
    @EntityGraph(attributePaths = {"payment", "payment.project", "mediaAsset"})
    Optional<ProjectPaymentAttachment> findByMediaAssetId(UUID mediaAssetId);
    @EntityGraph(attributePaths = {"payment", "mediaAsset"})
    List<ProjectPaymentAttachment> findByPaymentIdInOrderByPaymentIdAscCreatedAtAsc(List<Long> paymentIds);
}
