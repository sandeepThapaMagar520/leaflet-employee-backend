package com.ems.backend.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "project_task_boards",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_project_task_boards_project_key", columnNames = {"project_id", "status_key"}),
                @UniqueConstraint(name = "uk_project_task_boards_project_name", columnNames = {"project_id", "name"})
        }
)
public class ProjectTaskBoard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "status_key", nullable = false, length = 80)
    private String statusKey;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean defaultBoard = false;

    @Column(nullable = false)
    private boolean terminal = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
