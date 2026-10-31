CREATE TABLE judge_validation_test_results (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    validation_id BIGINT NOT NULL,
    test_asset_id BIGINT NOT NULL,
    test_order INT NOT NULL,
    judge0_token VARCHAR(64) NULL,
    token_status VARCHAR(30) NOT NULL,
    created_try_count INT NULL,
    verdict VARCHAR(30) NULL,
    time_ms INT NULL,
    memory_kb INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_judge_validation_test_results_job FOREIGN KEY (validation_id)
        REFERENCES judge_validation_jobs(validation_id),
    CONSTRAINT uk_judge_validation_test_results_order UNIQUE (validation_id, test_order),
    CONSTRAINT uk_judge_validation_test_results_asset UNIQUE (validation_id, test_asset_id),
    CONSTRAINT chk_validation_test_ids CHECK (test_asset_id > 0 AND test_order > 0),
    CONSTRAINT chk_validation_test_try CHECK (created_try_count BETWEEN 1 AND 3),
    CONSTRAINT chk_validation_test_token CHECK (token_status IN ('NOT_CREATED', 'CREATED', 'UNKNOWN', 'DONE')),
    CONSTRAINT chk_validation_test_verdict CHECK (verdict IN ('AC', 'WA', 'TLE', 'MLE', 'RE', 'CE', 'SYSTEM_ERROR')),
    CONSTRAINT chk_validation_test_metrics CHECK
        ((time_ms IS NULL OR time_ms >= 0) AND (memory_kb IS NULL OR memory_kb >= 0)),
    INDEX idx_judge_validation_test_results_token (token_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
