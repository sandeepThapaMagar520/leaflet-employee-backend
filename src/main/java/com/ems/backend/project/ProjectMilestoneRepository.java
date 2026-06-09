package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectMilestoneRepository extends JpaRepository<ProjectMilestone, Long> {
    List<ProjectMilestone> findByProjectIdOrderByDueDateAscCreatedAtAsc(Long projectId);
}
