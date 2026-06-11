package com.ems.backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {
    List<AttendanceSession> findByUserEmailIgnoreCaseOrderByStartTimeDesc(String email);
    List<AttendanceSession> findByUserIdAndEndTimeIsNull(Long userId);
    List<AttendanceSession> findAllByOrderByStartTimeDesc();

    @Query("""
            select session from AttendanceSession session
            where session.user.id = :userId
              and session.startTime < :to
              and (session.endTime is null or session.endTime >= :from)
            order by session.startTime asc
            """)
    List<AttendanceSession> findUserSessionsOverlappingDay(Long userId, Instant from, Instant to);

    @Query("""
            select session from AttendanceSession session
            where session.startTime < :to
              and (session.endTime is null or session.endTime >= :from)
            order by session.startTime asc
            """)
    List<AttendanceSession> findSessionsOverlappingDay(Instant from, Instant to);
}
