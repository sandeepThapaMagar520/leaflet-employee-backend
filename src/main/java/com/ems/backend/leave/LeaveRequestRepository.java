package com.ems.backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findAllByOrderByCreatedAtDesc();
    List<LeaveRequest> findByUserEmailIgnoreCaseOrderByCreatedAtDesc(String email);
    List<LeaveRequest> findByUserIdAndStatus(Long userId, LeaveStatus status);
}
