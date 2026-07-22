package com.ems.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy")
    List<Task> findAllWithDetails();

    @Query(value = "select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy",
            countQuery = "select count(t) from Task t")
    Page<Task> findAllWithDetails(Pageable pageable);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.id = :taskId")
    Optional<Task> findByIdWithDetails(Long taskId);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.id = :projectId")
    List<Task> findByProjectId(Long projectId);

    @Query(value = "select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.id = :projectId",
            countQuery = "select count(t) from Task t where t.project.id = :projectId")
    Page<Task> findByProjectId(Long projectId, Pageable pageable);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where lower(t.assignedTo.email) = lower(:email)")
    List<Task> findByAssignedToEmailIgnoreCase(String email);

    @Query(value = "select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.assignedTo.id = :userId",
            countQuery = "select count(t) from Task t where t.assignedTo.id = :userId")
    Page<Task> findByAssignedToIdWithDetails(Long userId, Pageable pageable);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.manager.id = :managerId")
    List<Task> findByProjectManagerId(Long managerId);

    @Query(value = "select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.manager.id = :managerId",
            countQuery = "select count(t) from Task t where t.project.manager.id = :managerId")
    Page<Task> findByProjectManagerId(Long managerId, Pageable pageable);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.assignedTo.id = :userId")
    List<Task> findByAssignedToIdWithDetails(Long userId);

    List<Task> findByStatusNotAndDueDateIsNotNull(String status);

    @Query("""
            select t.project.id as projectId, count(t) as totalCount,
                   sum(case when t.status = 'DONE' then 1 else 0 end) as doneCount
            from Task t where t.project.id in :projectIds group by t.project.id
            """)
    List<ProjectTaskProgressRow> summarizeByProjectIds(List<Long> projectIds);

    interface ProjectTaskProgressRow {
        Long getProjectId();
        long getTotalCount();
        long getDoneCount();
    }
}
