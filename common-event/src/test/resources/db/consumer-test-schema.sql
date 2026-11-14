-- 서비스 Flyway 이력과 분리한 소비 트랜잭션 검증 전용 스키마다.
CREATE TABLE processed_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_processed_events_event_id UNIQUE (event_id),
    INDEX idx_processed_events_processed_at (processed_at)
);

-- 업무 UNIQUE 오류와 이벤트 중복을 구분할 테스트용 테이블이다.
CREATE TABLE consumer_business_records (
    id BIGINT NOT NULL PRIMARY KEY,
    business_key VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    CONSTRAINT uk_consumer_business_key UNIQUE (business_key)
);
