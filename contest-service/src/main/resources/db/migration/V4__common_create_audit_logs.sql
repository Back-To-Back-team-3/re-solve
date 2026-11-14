CREATE TABLE audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL COMMENT 'members.id',
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    before_state JSON NULL,
    after_state JSON NULL,
    reason VARCHAR(500) NOT NULL,
    client_ip VARCHAR(45) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_audit_logs_target (target_type, target_id, created_at),
    INDEX idx_audit_logs_actor_created_at (actor_id, created_at)
);
