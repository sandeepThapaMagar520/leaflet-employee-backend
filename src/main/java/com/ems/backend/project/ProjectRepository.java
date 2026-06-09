package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("select p from Project p join fetch p.manager join fetch p.createdBy order by p.createdAt desc")
    List<Project> findAllWithDetails();

    @Query("select p from Project p join fetch p.manager join fetch p.createdBy left join fetch p.assignedEmployees where p.id = :id")
    Optional<Project> findByIdWithDetails(Long id);

    @Query("""
            select distinct p from Project p
            join fetch p.manager
            join fetch p.createdBy
            left join fetch p.assignedEmployees ae
            where p.manager.id = :userId or ae.id = :userId
            order by p.createdAt desc
            """)
    List<Project> findAllAccessibleToUser(Long userId);
}
