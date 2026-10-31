CREATE TABLE judge_validation_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    validation_id BIGINT NOT NULL,
    try_count INT NOT NULL,
    lease_token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    runner_version VARCHAR(100) NULL,
    result VARCHAR(30) NOT NULL,
    verdict VARCHAR(30) NULL,
    max_time_ms INT NULL,
    error_message VARCHAR(500) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_judge_validation_runs_job FOREIGN KEY (validation_id) REFERENCES judge_validation_jobs(validation_id),
    CONSTRAINT uk_judge_validation_runs_try UNIQUE (validation_id, try_count),
    CONSTRAINT chk_validation_runs_tries CHECK (try_count BETWEEN 1 AND 3),
    CONSTRAINT chk_validation_runs_result CHECK (result IN ('SUCCEEDED', 'SYSTEM_ERROR', 'LEASE_EXPIRED')),
    CONSTRAINT chk_validation_runs_verdict CHECK (verdict IN ('AC', 'WA', 'TLE', 'MLE', 'RE', 'CE')),
    CONSTRAINT chk_validation_runs_success CHECK
        ((result = 'SUCCEEDED' AND verdict IS NOT NULL AND runner_version IS NOT NULL)
        OR (result <> 'SUCCEEDED' AND verdict IS NULL)),
    CONSTRAINT chk_validation_runs_time CHECK (finished_at >= started_at AND (max_time_ms IS NULL OR max_time_ms >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
