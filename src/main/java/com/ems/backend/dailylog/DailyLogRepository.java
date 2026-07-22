package com.ems.backend.dailylog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyLogRepository extends JpaRepository<DailyLog, Long> {
    @Query("select log from DailyLog log join fetch log.user where lower(log.user.email) = lower(:email) order by log.logDate desc")
    List<DailyLog> findByUserEmailIgnoreCaseOrderByLogDateDesc(String email);

    @Query(value = "select log from DailyLog log join fetch log.user where log.user.id = :userId",
            countQuery = "select count(log) from DailyLog log where log.user.id = :userId")
    Page<DailyLog> findByUserId(Long userId, Pageable pageable);

    @Query("select log from DailyLog log join fetch log.user order by log.logDate desc")
    List<DailyLog> findAllByOrderByLogDateDesc();

    @Query(value = "select log from DailyLog log join fetch log.user where log.user.role <> com.ems.backend.user.Role.ADMIN",
            countQuery = "select count(log) from DailyLog log where log.user.role <> com.ems.backend.user.Role.ADMIN")
    Page<DailyLog> findAllNonAdmin(Pageable pageable);

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

    @Query(value = """
            select log from DailyLog log join fetch log.user
            where log.user.id = :managerId or exists (
                select scope.id from ManagerEmployeeScope scope where scope.manager.id = :managerId
                and scope.employee.id = log.user.id and scope.active = true)
            """, countQuery = """
            select count(log) from DailyLog log where log.user.id = :managerId or exists (
                select scope.id from ManagerEmployeeScope scope where scope.manager.id = :managerId
                and scope.employee.id = log.user.id and scope.active = true)
            """)
    Page<DailyLog> findVisibleToManager(Long managerId, Pageable pageable);

    @Query(value = "select log from DailyLog log join fetch log.user where log.user.role <> com.ems.backend.user.Role.ADMIN and log.logDate between :from and :to",
            countQuery = "select count(log) from DailyLog log where log.user.role <> com.ems.backend.user.Role.ADMIN and log.logDate between :from and :to")
    Page<DailyLog> findAllNonAdminBetween(LocalDate from, LocalDate to, Pageable pageable);

    @Query(value = """
            select log from DailyLog log join fetch log.user where log.logDate between :from and :to and
            (log.user.id = :managerId or exists (select scope.id from ManagerEmployeeScope scope
             where scope.manager.id = :managerId and scope.employee.id = log.user.id and scope.active = true))
            """, countQuery = """
            select count(log) from DailyLog log where log.logDate between :from and :to and
            (log.user.id = :managerId or exists (select scope.id from ManagerEmployeeScope scope
             where scope.manager.id = :managerId and scope.employee.id = log.user.id and scope.active = true))
            """)
    Page<DailyLog> findVisibleToManagerBetween(Long managerId, LocalDate from, LocalDate to, Pageable pageable);

    @Query(value = "select log from DailyLog log join fetch log.user where log.user.id = :userId and log.logDate between :from and :to",
            countQuery = "select count(log) from DailyLog log where log.user.id = :userId and log.logDate between :from and :to")
    Page<DailyLog> findByUserIdBetween(Long userId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("select log from DailyLog log join fetch log.user where log.user.id = :userId order by log.logDate desc")
    List<DailyLog> findByUserIdOrderByLogDateDesc(Long userId);

    boolean existsByUserIdAndLogDate(Long userId, LocalDate logDate);
    boolean existsByUserIdAndLogDateAndIdNot(Long userId, LocalDate logDate, Long id);
}
