CREATE TABLE outbox_events
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(64)  NOT NULL COMMENT '이벤트 식별자 (UUID v4)',
    event_type      VARCHAR(50)  NOT NULL COMMENT '이벤트명 (예: SubmissionJudged)',
    schema_version  VARCHAR(10)  NOT NULL COMMENT '메시지 형식 버전 (v1)',
    aggregate_type  VARCHAR(30)  NOT NULL COMMENT '대상 애그리게이트 타입 (EXAM, SUBMISSION 등)',
    aggregate_id    VARCHAR(64)  NOT NULL COMMENT '대상 애그리게이트 ID',
    correlation_id  VARCHAR(64)  NOT NULL COMMENT '요청·메시지 추적 ID',
    payload         JSON         NOT NULL COMMENT '이벤트 메시지 본문',
    status          VARCHAR(30)  NOT NULL COMMENT 'PENDING / PUBLISHED / FAILED',
    retry_count     INT          NOT NULL COMMENT '발행 재시도 횟수',
    occurred_at     DATETIME(6)  NOT NULL COMMENT '이벤트 발생 시각',
    published_at    DATETIME(6)  NULL COMMENT 'NULL 허용: 발행 전',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id),
    INDEX idx_outbox_events_status_created_at (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
