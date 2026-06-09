package com.ems.backend.dailylog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyLogRepository extends JpaRepository<DailyLog, Long> {
    List<DailyLog> findByUserEmailIgnoreCaseOrderByLogDateDesc(String email);
    List<DailyLog> findAllByOrderByLogDateDesc();
    boolean existsByUserIdAndLogDate(Long userId, LocalDate logDate);
    boolean existsByUserIdAndLogDateAndIdNot(Long userId, LocalDate logDate, Long id);
}
