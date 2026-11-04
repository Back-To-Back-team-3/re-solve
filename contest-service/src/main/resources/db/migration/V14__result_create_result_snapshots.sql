CREATE TABLE result_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    context VARCHAR(30) NOT NULL,
    context_id BIGINT NOT NULL,
    result_revision BIGINT NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_result_snapshots_context_revision UNIQUE (context, context_id, result_revision),
    INDEX idx_result_snapshots_context_id (context, context_id)
);
