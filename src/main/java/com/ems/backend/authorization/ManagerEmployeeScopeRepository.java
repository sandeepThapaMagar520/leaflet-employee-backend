package com.ems.backend.authorization;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ManagerEmployeeScopeRepository extends JpaRepository<ManagerEmployeeScope, Long> {
    boolean existsByManagerIdAndEmployeeIdAndActiveTrue(Long managerId, Long employeeId);

    long countByEmployeeIdAndActiveTrue(Long employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"manager", "employee", "assignedBy"})
    @Query("""
            select scope from ManagerEmployeeScope scope
            where scope.employee.id = :employeeId and scope.active = true
            """)
    Optional<ManagerEmployeeScope> findActiveByEmployeeIdForUpdate(@Param("employeeId") Long employeeId);

    @EntityGraph(attributePaths = {"manager", "employee", "assignedBy"})
    Optional<ManagerEmployeeScope> findByEmployeeIdAndActiveTrue(Long employeeId);

    @EntityGraph(attributePaths = {"manager", "employee", "assignedBy"})
    Page<ManagerEmployeeScope> findByManagerIdAndActiveTrue(Long managerId, Pageable pageable);
}
