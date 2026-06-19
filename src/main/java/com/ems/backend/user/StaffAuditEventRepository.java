package com.ems.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffAuditEventRepository extends JpaRepository<StaffAuditEvent, Long> {
    List<StaffAuditEvent> findByStaffUserIdOrderByCreatedAtDesc(Long staffUserId);
}
