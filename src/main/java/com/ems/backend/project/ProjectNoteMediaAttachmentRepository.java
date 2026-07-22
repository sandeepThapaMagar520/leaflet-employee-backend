package com.ems.backend.project;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectNoteMediaAttachmentRepository extends JpaRepository<ProjectNoteMediaAttachment, Long> {
    @EntityGraph(attributePaths = {"mediaAsset"})
    List<ProjectNoteMediaAttachment> findByNoteIdOrderByDisplayOrder(Long noteId);
    @EntityGraph(attributePaths = {"note", "mediaAsset"})
    List<ProjectNoteMediaAttachment> findByNoteIdInOrderByNoteIdAscDisplayOrderAsc(List<Long> noteIds);
    @EntityGraph(attributePaths = {"note", "note.project", "mediaAsset"})
    Optional<ProjectNoteMediaAttachment> findByMediaAssetId(UUID mediaAssetId);
}
