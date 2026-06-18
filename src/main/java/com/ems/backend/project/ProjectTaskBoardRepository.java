package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectTaskBoardRepository extends JpaRepository<ProjectTaskBoard, Long> {
    List<ProjectTaskBoard> findByProjectIdOrderByDisplayOrderAscIdAsc(Long projectId);
    Optional<ProjectTaskBoard> findByProjectIdAndStatusKey(Long projectId, String statusKey);
    boolean existsByProjectIdAndStatusKey(Long projectId, String statusKey);
    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);
    int countByProjectId(Long projectId);
}
