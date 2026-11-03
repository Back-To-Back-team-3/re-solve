package com.backtoback.member.admin.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.member.admin.domain.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

}
