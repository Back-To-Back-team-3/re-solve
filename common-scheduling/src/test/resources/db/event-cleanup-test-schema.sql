-- 서비스 Flyway 이력과 분리한 이벤트 정리 검증용 스키마다.
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

CREATE TABLE processed_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_processed_events_event_id UNIQUE (event_id),
    INDEX idx_processed_events_processed_at (processed_at)
);

-- 정리가 감사 테이블에 접근하지 않는지 확인할 최소 테스트용 보존 행이다.
CREATE TABLE audit_logs (id BIGINT NOT NULL PRIMARY KEY);
INSERT INTO audit_logs (id) VALUES (1);
