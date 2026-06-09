package com.ems.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProjectNoteRepository extends JpaRepository<ProjectNote, Long> {

    @Query("select n from ProjectNote n join fetch n.createdBy join fetch n.project where n.id = :noteId")
    Optional<ProjectNote> findByIdWithDetails(Long noteId);
    
    @Query("select n from ProjectNote n join fetch n.createdBy where n.project.id = :projectId order by n.createdAt desc")
    List<ProjectNote> findAllByProjectIdWithCreatorOrderByCreatedAtDesc(Long projectId);
    
    @Query("select n from ProjectNote n join fetch n.createdBy where n.project.id = :projectId and n.noteType = :type order by n.createdAt desc")
    List<ProjectNote> findAllByProjectIdAndNoteTypeWithCreatorOrderByCreatedAtDesc(Long projectId, ProjectNoteType type);
}
