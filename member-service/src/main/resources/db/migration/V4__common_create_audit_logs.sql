CREATE TABLE audit_logs
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id    BIGINT       NOT NULL COMMENT '작업을 수행한 관리자/사용자 ID (members.id)',
    action      VARCHAR(50)  NOT NULL COMMENT '수행 행위 (예: FORCE_EXAM_CLOSED)',
    target_type VARCHAR(30)  NOT NULL COMMENT '대상 리소스 타입',
    target_id   VARCHAR(64)  NOT NULL COMMENT '대상 리소스 식별자',
    before_state JSON        NULL COMMENT 'NULL 허용: 생성 작업',
    after_state JSON         NULL COMMENT 'NULL 허용: 삭제 성격 작업',
    reason      VARCHAR(500) NOT NULL COMMENT '조치 사유',
    client_ip   VARCHAR(45)  NULL COMMENT 'NULL 허용: 스케줄러·내부 처리',
    created_at  DATETIME(6)  NOT NULL COMMENT '발생 시각',
    PRIMARY KEY (id),
    INDEX idx_audit_logs_target (target_type, target_id, created_at),
    INDEX idx_audit_logs_actor_created_at (actor_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
