CREATE TABLE processed_events
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_id     VARCHAR(64)  NOT NULL COMMENT '수신한 이벤트 식별자',
    event_type   VARCHAR(50)  NOT NULL COMMENT '이벤트명',
    processed_at DATETIME(6)  NOT NULL COMMENT '소비 처리 완료 시각',
    created_at   DATETIME(6)  NOT NULL COMMENT '수신 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_processed_events_event_id UNIQUE (event_id),
    INDEX idx_processed_events_processed_at (processed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
