package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @Query(value = """
            SELECT COALESCE(can_manage_tasks, FALSE)
            FROM project_assignments
            WHERE project_id = :projectId AND user_id = :userId
            """, nativeQuery = true)
    Boolean canMemberManageTasks(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Query(value = """
            SELECT COALESCE(can_add_notes, FALSE)
            FROM project_assignments
            WHERE project_id = :projectId AND user_id = :userId
            """, nativeQuery = true)
    Boolean canMemberAddNotes(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Modifying
    @Query(value = """
            UPDATE project_assignments
            SET can_manage_tasks = :canManageTasks, can_add_notes = :canAddNotes
            WHERE project_id = :projectId AND user_id = :userId
            """, nativeQuery = true)
    int updateMemberPermissions(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("canManageTasks") boolean canManageTasks,
            @Param("canAddNotes") boolean canAddNotes
    );

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
