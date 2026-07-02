package com.ems.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy")
    List<Task> findAllWithDetails();

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.id = :taskId")
    Optional<Task> findByIdWithDetails(Long taskId);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.id = :projectId")
    List<Task> findByProjectId(Long projectId);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where lower(t.assignedTo.email) = lower(:email)")
    List<Task> findByAssignedToEmailIgnoreCase(String email);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.project.manager.id = :managerId")
    List<Task> findByProjectManagerId(Long managerId);

    @Query("select t from Task t join fetch t.project join fetch t.assignedTo join fetch t.createdBy where t.assignedTo.id = :userId")
    List<Task> findByAssignedToIdWithDetails(Long userId);

    List<Task> findByStatusNotAndDueDateIsNotNull(String status);
}
