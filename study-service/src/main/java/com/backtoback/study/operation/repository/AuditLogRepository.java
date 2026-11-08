package com.backtoback.study.operation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.operation.domain.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

}
