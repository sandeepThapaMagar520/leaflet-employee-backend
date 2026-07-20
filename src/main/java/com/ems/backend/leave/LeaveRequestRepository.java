package com.ems.backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer order by request.createdAt desc")
    List<LeaveRequest> findAllByOrderByCreatedAtDesc();

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where lower(request.user.email) = lower(:email) order by request.createdAt desc")
    List<LeaveRequest> findByUserEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where request.user.id = :userId order by request.createdAt desc")
    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            select request from LeaveRequest request
            join fetch request.user
            left join fetch request.reviewer
            where request.user.id = :managerId
               or exists (
                select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId
                  and scope.employee.id = request.user.id
                  and scope.active = true
            )
            order by request.createdAt desc
            """)
    List<LeaveRequest> findVisibleToManager(Long managerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from LeaveRequest request
            join fetch request.user
            where request.id = :id
            """)
    java.util.Optional<LeaveRequest> findByIdForUpdate(Long id);

    @Query("select request from LeaveRequest request join fetch request.user left join fetch request.reviewer where request.user.id = :userId and request.status = :status")
    List<LeaveRequest> findByUserIdAndStatus(Long userId, LeaveStatus status);

    @Query("""
            select request from LeaveRequest request
            where request.user.id = :userId
              and request.leaveType = :leaveType
              and request.status = com.ems.backend.leave.LeaveStatus.APPROVED
              and request.startDate <= :endDate
              and request.endDate >= :startDate
            """)
    List<LeaveRequest> findApprovedOverlapping(
            Long userId,
            LeaveType leaveType,
            LocalDate startDate,
            LocalDate endDate
    );

    @Query("""
            select count(request) > 0 from LeaveRequest request
            where request.user.id = :userId
              and request.status in (
                com.ems.backend.leave.LeaveStatus.PENDING,
                com.ems.backend.leave.LeaveStatus.APPROVED
              )
              and request.startDate <= :endDate
              and request.endDate >= :startDate
            """)
    boolean existsBlockingOverlap(Long userId, LocalDate startDate, LocalDate endDate);

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

    boolean existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId,
            LeaveStatus status,
            LocalDate endDate,
            LocalDate startDate
    );

    @Query("""
            select request from LeaveRequest request
            join fetch request.user
            left join fetch request.reviewer
            where request.status = :status
              and request.startDate <= :endDate
              and request.endDate >= :startDate
              and request.user.id in :userIds
            """)
    List<LeaveRequest> findOverlappingForUsers(
            LeaveStatus status,
            LocalDate endDate,
            LocalDate startDate,
            List<Long> userIds
    );
}
