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
    exam_redrive_task_digest BINARY(32) GENERATED ALWAYS AS (
        CASE WHEN action = 'DLQ_REDRIVE' AND target_type = 'EXAM_SUBMISSION_REQUEST'
             THEN UNHEX(SHA2(JSON_UNQUOTE(JSON_EXTRACT(after_state, '$.moveTaskHandle')), 256)) END
    ) STORED,
    UNIQUE INDEX uk_audit_logs_exam_redrive (target_id, exam_redrive_task_digest),
    INDEX idx_audit_logs_target (target_type, target_id, created_at),
    INDEX idx_audit_logs_actor_created_at (actor_id, created_at)
);
