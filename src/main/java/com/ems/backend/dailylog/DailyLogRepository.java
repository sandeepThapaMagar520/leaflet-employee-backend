package com.ems.backend.dailylog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyLogRepository extends JpaRepository<DailyLog, Long> {
    @Query("select log from DailyLog log join fetch log.user where lower(log.user.email) = lower(:email) order by log.logDate desc")
    List<DailyLog> findByUserEmailIgnoreCaseOrderByLogDateDesc(String email);

    @Query("select log from DailyLog log join fetch log.user order by log.logDate desc")
    List<DailyLog> findAllByOrderByLogDateDesc();

    @Query("""
            select log from DailyLog log join fetch log.user
            where log.user.id = :managerId
               or exists (
                select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId
                  and scope.employee.id = log.user.id
                  and scope.active = true
            )
            order by log.logDate desc
            """)
    List<DailyLog> findVisibleToManager(Long managerId);

    @Query("select log from DailyLog log join fetch log.user where log.user.id = :userId order by log.logDate desc")
    List<DailyLog> findByUserIdOrderByLogDateDesc(Long userId);

    boolean existsByUserIdAndLogDate(Long userId, LocalDate logDate);
    boolean existsByUserIdAndLogDateAndIdNot(Long userId, LocalDate logDate, Long id);
}
