package com.ems.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByProjectId(Long projectId);
    List<Task> findByAssignedToEmailIgnoreCase(String email);
    List<Task> findByProjectManagerId(Long managerId);
    List<Task> findByStatusNotAndDueDateIsNotNull(String status);
}
