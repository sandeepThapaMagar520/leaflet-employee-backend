package com.ems.backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer order by request.createdAt desc")
    List<LeaveRequest> findAllByOrderByCreatedAtDesc();

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where lower(request.user.email) = lower(:email) order by request.createdAt desc")
    List<LeaveRequest> findByUserEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where request.user.id = :userId order by request.createdAt desc")
    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where request.user.id = :userId and request.status = :status")
    List<LeaveRequest> findByUserIdAndStatus(Long userId, LeaveStatus status);

    @Query("""
            select request from LeaveRequest request
            join fetch request.user
            left join fetch request.reviewer
            where request.status = :status
              and request.startDate <= :endDate
              and request.endDate >= :startDate
            """)
    List<LeaveRequest> findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LeaveStatus status,
            LocalDate endDate,
            LocalDate startDate
    );
}
