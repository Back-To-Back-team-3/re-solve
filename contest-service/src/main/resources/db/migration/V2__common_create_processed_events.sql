CREATE TABLE processed_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_processed_events_event_id UNIQUE (event_id),
    INDEX idx_processed_events_processed_at (processed_at)
);
