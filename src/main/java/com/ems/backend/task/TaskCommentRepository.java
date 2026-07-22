package com.ems.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {
    List<TaskComment> findByTaskIdOrderByCreatedAtDesc(Long taskId);
    @EntityGraph(attributePaths = {"task", "user", "mediaAsset"})
    Page<TaskComment> findByTaskId(Long taskId, Pageable pageable);
    Optional<TaskComment> findByMediaAssetId(UUID mediaAssetId);
}
