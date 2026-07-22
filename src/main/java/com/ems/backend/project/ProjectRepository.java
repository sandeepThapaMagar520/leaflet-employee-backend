package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByDocumentMediaAssetId(UUID mediaAssetId);
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

    @Query("select distinct p from Project p join fetch p.manager join fetch p.createdBy left join fetch p.assignedEmployees order by p.createdAt desc")
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

    @Query("""
            select distinct p from Project p
            join fetch p.manager
            join fetch p.createdBy
            left join fetch p.assignedEmployees ae
            where p.manager.id = :userId
               or ae.id = :userId
            order by p.createdAt desc
            """)
    List<Project> findAllForStaffMember(Long userId);

    @Query(value = "select p.id from Project p", countQuery = "select count(p) from Project p")
    Page<Long> findAllProjectIds(Pageable pageable);

    @Query(value = """
            select p.id from Project p
            where p.manager.id = :userId or exists (
                select assigned.id from Project assigned join assigned.assignedEmployees member
                where assigned.id = p.id and member.id = :userId
            )
            """,
            countQuery = """
            select count(p) from Project p
            where p.manager.id = :userId or exists (
                select assigned.id from Project assigned join assigned.assignedEmployees member
                where assigned.id = p.id and member.id = :userId
            )
            """)
    Page<Long> findAccessibleProjectIds(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            select distinct p from Project p
            join fetch p.manager
            join fetch p.createdBy
            left join fetch p.assignedEmployees
            where p.id in :ids
            """)
    List<Project> findAllWithDetailsByIdIn(@Param("ids") List<Long> ids);

    @Query(value = """
            select project_id as projectId, user_id as userId,
                   can_manage_tasks as canManageTasks, can_add_notes as canAddNotes
            from project_assignments where project_id in (:projectIds)
            """, nativeQuery = true)
    List<MemberPermissionRow> findMemberPermissions(@Param("projectIds") List<Long> projectIds);

    interface MemberPermissionRow {
        Long getProjectId();
        Long getUserId();
        Boolean getCanManageTasks();
        Boolean getCanAddNotes();
    }
}
