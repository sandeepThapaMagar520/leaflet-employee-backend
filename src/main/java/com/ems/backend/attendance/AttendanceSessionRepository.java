package com.ems.backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {
    List<AttendanceSession> findByUserEmailIgnoreCaseOrderByStartTimeDesc(String email);
    List<AttendanceSession> findByUserIdAndEndTimeIsNull(Long userId);
    List<AttendanceSession> findAllByOrderByStartTimeDesc();
}
