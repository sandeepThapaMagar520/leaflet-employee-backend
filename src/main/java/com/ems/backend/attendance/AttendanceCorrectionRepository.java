package com.ems.backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrectionRequest, Long> {
    List<AttendanceCorrectionRequest> findAllByOrderByCreatedAtDesc();
    List<AttendanceCorrectionRequest> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByAttendanceSessionIdAndStatus(Long sessionId, AttendanceCorrectionStatus status);
}
