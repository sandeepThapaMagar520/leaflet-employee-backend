package com.ems.backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {
    @Query("select session from AttendanceSession session join fetch session.user where lower(session.user.email) = lower(:email) order by session.startTime desc")
    List<AttendanceSession> findByUserEmailIgnoreCaseOrderByStartTimeDesc(String email);

    @Query("select session from AttendanceSession session join fetch session.user where session.user.id = :userId and session.endTime is null")
    List<AttendanceSession> findByUserIdAndEndTimeIsNull(Long userId);

    @Query("select session from AttendanceSession session join fetch session.user order by session.startTime desc")
    List<AttendanceSession> findAllByOrderByStartTimeDesc();

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
}
