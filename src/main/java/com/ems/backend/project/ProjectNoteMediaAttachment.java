package com.ems.backend.project;

import com.ems.backend.media.MediaAsset;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "project_note_media_attachments")
public class ProjectNoteMediaAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private ProjectNote note;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset mediaAsset;
    @Column(nullable = false)
    private int displayOrder;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist
    void create() { createdAt = Instant.now(); }
}
