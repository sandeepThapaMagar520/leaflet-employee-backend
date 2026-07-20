package com.ems.backend.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPaymentRepository extends JpaRepository<ProjectPayment, Long> {
    Optional<ProjectPayment> findByProjectIdAndIdempotencyKey(Long projectId, UUID idempotencyKey);

    @Query("select p from ProjectPayment p join fetch p.createdBy where p.project.id = :projectId order by p.paidAt desc")
    List<ProjectPayment> findAllByProjectIdWithCreatorOrderByPaidAtDesc(@Param("projectId") Long projectId);

    @Query("select p from ProjectPayment p join fetch p.createdBy where p.project.id = :projectId order by p.paidAt desc")
    List<ProjectPayment> findAllByProjectIdWithCreatorOrderByPaidAtDesc(@Param("projectId") Long projectId, Pageable pageable);

    @Query("select coalesce(sum(p.amount), 0) from ProjectPayment p where p.project.id = :projectId")
    BigDecimal sumAmountByProjectId(@Param("projectId") Long projectId);

    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("""
            select p from ProjectPayment p
            join fetch p.createdBy
            where p.id = :paymentId and p.project.id = :projectId
            """)
    Optional<ProjectPayment> findByIdAndProjectIdForAttachmentUpdate(
            @Param("paymentId") Long paymentId,
            @Param("projectId") Long projectId
    );

    default Optional<ProjectPayment> findLatestByProjectId(Long projectId) {
        List<ProjectPayment> rows = findAllByProjectIdWithCreatorOrderByPaidAtDesc(projectId, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
