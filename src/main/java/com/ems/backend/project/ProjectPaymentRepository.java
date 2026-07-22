package com.ems.backend.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
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

    @Query(value = "select p from ProjectPayment p join fetch p.createdBy where p.project.id = :projectId",
            countQuery = "select count(p) from ProjectPayment p where p.project.id = :projectId")
    Page<ProjectPayment> findPageByProjectId(@Param("projectId") Long projectId, Pageable pageable);

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

    @Query("""
            select p.project.id as projectId, coalesce(sum(p.amount), 0) as totalPaid
            from ProjectPayment p where p.project.id in :projectIds group by p.project.id
            """)
    List<ProjectPaymentTotalRow> sumByProjectIds(@Param("projectIds") List<Long> projectIds);

    @Query(value = """
            select distinct on (project_id) project_id as projectId, amount as amount,
                   paid_at as paidAt, reference_note as referenceNote
            from project_payments
            where project_id in (:projectIds)
            order by project_id, paid_at desc, id desc
            """, nativeQuery = true)
    List<LatestProjectPaymentRow> findLatestByProjectIds(@Param("projectIds") List<Long> projectIds);

    interface ProjectPaymentTotalRow {
        Long getProjectId();
        BigDecimal getTotalPaid();
    }

    interface LatestProjectPaymentRow {
        Long getProjectId();
        BigDecimal getAmount();
        java.time.Instant getPaidAt();
        String getReferenceNote();
    }
}
