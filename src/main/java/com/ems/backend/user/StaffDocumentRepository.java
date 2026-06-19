package com.ems.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, Long> {
    List<StaffDocument> findByUserIdOrderByCreatedAtDesc(Long userId);
}
