package com.ems.backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findAllByOrderByCreatedAtDesc();
    List<LeaveRequest> findByUserEmailIgnoreCaseOrderByCreatedAtDesc(String email);
    List<LeaveRequest> findByUserIdAndStatus(Long userId, LeaveStatus status);
    List<LeaveRequest> findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LeaveStatus status,
            LocalDate endDate,
            LocalDate startDate
    );
}
