package com.ems.backend.attendance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {
    @Query("select session from AttendanceSession session join fetch session.user where lower(session.user.email) = lower(:email) order by session.startTime desc")
    List<AttendanceSession> findByUserEmailIgnoreCaseOrderByStartTimeDesc(String email);

    @Query(value = "select session from AttendanceSession session join fetch session.user where session.user.id = :userId",
            countQuery = "select count(session) from AttendanceSession session where session.user.id = :userId")
    Page<AttendanceSession> findByUserId(Long userId, Pageable pageable);

    @Query("select session from AttendanceSession session join fetch session.user where session.user.id = :userId and session.endTime is null")
    List<AttendanceSession> findByUserIdAndEndTimeIsNull(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AttendanceSession session where session.user.id = :userId and session.endTime is null")
    Optional<AttendanceSession> findActiveByUserIdForUpdate(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AttendanceSession session where session.id = :id")
    Optional<AttendanceSession> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session from AttendanceSession session
            where session.user.id = :userId
              and session.id <> :excludedSessionId
              and session.startTime < :endTime
              and (session.endTime is null or session.endTime > :startTime)
            order by session.id
            """)
    List<AttendanceSession> findOverlappingForUpdate(
            Long userId,
            Long excludedSessionId,
            Instant startTime,
            Instant endTime
    );

    @Query("select session from AttendanceSession session join fetch session.user order by session.startTime desc")
    List<AttendanceSession> findAllByOrderByStartTimeDesc();

    @Query(value = "select session from AttendanceSession session join fetch session.user where session.user.role <> com.ems.backend.user.Role.ADMIN",
            countQuery = "select count(session) from AttendanceSession session where session.user.role <> com.ems.backend.user.Role.ADMIN")
    Page<AttendanceSession> findAllNonAdmin(Pageable pageable);

    @Query("""
            select session from AttendanceSession session join fetch session.user
            where exists (
                select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId
                  and scope.employee.id = session.user.id
                  and scope.active = true
            )
            order by session.startTime desc
            """)
    List<AttendanceSession> findVisibleToManager(Long managerId);

    @Query(value = """
            select session from AttendanceSession session join fetch session.user
            where exists (select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId and scope.employee.id = session.user.id and scope.active = true)
            """, countQuery = """
            select count(session) from AttendanceSession session
            where exists (select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId and scope.employee.id = session.user.id and scope.active = true)
            """)
    Page<AttendanceSession> findVisibleToManager(Long managerId, Pageable pageable);

    @Query(value = """
            select session from AttendanceSession session join fetch session.user
            where session.user.role <> com.ems.backend.user.Role.ADMIN
              and session.startTime >= :from and session.startTime < :to
            """, countQuery = """
            select count(session) from AttendanceSession session
            where session.user.role <> com.ems.backend.user.Role.ADMIN
              and session.startTime >= :from and session.startTime < :to
            """)
    Page<AttendanceSession> findAllNonAdminBetween(Instant from, Instant to, Pageable pageable);

    @Query(value = """
            select session from AttendanceSession session join fetch session.user
            where session.startTime >= :from and session.startTime < :to and exists (
                select scope.id from ManagerEmployeeScope scope where scope.manager.id = :managerId
                and scope.employee.id = session.user.id and scope.active = true)
            """, countQuery = """
            select count(session) from AttendanceSession session
            where session.startTime >= :from and session.startTime < :to and exists (
                select scope.id from ManagerEmployeeScope scope where scope.manager.id = :managerId
                and scope.employee.id = session.user.id and scope.active = true)
            """)
    Page<AttendanceSession> findVisibleToManagerBetween(Long managerId, Instant from, Instant to, Pageable pageable);

    @Query(value = "select session from AttendanceSession session join fetch session.user where session.user.id = :userId and session.startTime >= :from and session.startTime < :to",
            countQuery = "select count(session) from AttendanceSession session where session.user.id = :userId and session.startTime >= :from and session.startTime < :to")
    Page<AttendanceSession> findByUserIdBetween(Long userId, Instant from, Instant to, Pageable pageable);

    @Query("select session from AttendanceSession session join fetch session.user where session.user.id = :userId order by session.startTime desc")
    List<AttendanceSession> findByUserIdOrderByStartTimeDesc(Long userId);

    @Query("""
            select session from AttendanceSession session join fetch session.user
            where session.user.id = :userId
              and session.startTime < :to
              and (session.endTime is null or session.endTime >= :from)
            order by session.startTime asc
            """)
    List<AttendanceSession> findUserSessionsOverlappingDay(Long userId, Instant from, Instant to);

    @Query("""
            select session from AttendanceSession session join fetch session.user
            where session.startTime < :to
              and (session.endTime is null or session.endTime >= :from)
            order by session.startTime asc
            """)
    List<AttendanceSession> findSessionsOverlappingDay(Instant from, Instant to);

    @Query("""
            select session from AttendanceSession session join fetch session.user
            where session.user.id in :userIds
              and session.startTime < :to
              and (session.endTime is null or session.endTime >= :from)
            order by session.startTime asc
            """)
    List<AttendanceSession> findSessionsOverlappingDayForUsers(
            List<Long> userIds,
            Instant from,
            Instant to
    );
}
