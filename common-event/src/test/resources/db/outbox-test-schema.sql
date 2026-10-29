-- 공통 테이블은 서비스가 생성한다. 이 스키마는 격리된 MySQL 테스트에서만 사용한다.
CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(30) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id),
    INDEX idx_outbox_events_status_created_at (status, created_at)
);

-- 업무 변경과 Outbox 저장의 커밋·롤백 경계를 확인하는 테스트 전용 테이블이다.
CREATE TABLE business_records (
    id BIGINT NOT NULL PRIMARY KEY,
    status VARCHAR(30) NOT NULL
);
