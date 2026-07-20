package com.ems.backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrectionRequest, Long> {
    List<AttendanceCorrectionRequest> findAllByOrderByCreatedAtDesc();
    List<AttendanceCorrectionRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            select correction from AttendanceCorrectionRequest correction
            join fetch correction.user
            join fetch correction.attendanceSession
            left join fetch correction.reviewer
            where correction.user.id = :managerId
               or exists (
                select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId
                  and scope.employee.id = correction.user.id
                  and scope.active = true
            )
            order by correction.createdAt desc
            """)
    List<AttendanceCorrectionRequest> findVisibleToManager(Long managerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select correction from AttendanceCorrectionRequest correction
            join fetch correction.user
            join fetch correction.attendanceSession
            where correction.id = :id
            """)
    java.util.Optional<AttendanceCorrectionRequest> findByIdForUpdate(Long id);

    boolean existsByAttendanceSessionIdAndStatus(Long sessionId, AttendanceCorrectionStatus status);
}
