CREATE TABLE judge_job_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    judge_job_id BIGINT NOT NULL,
    try_count INT NOT NULL,
    lease_token VARCHAR(64) NOT NULL,
    result VARCHAR(30) NOT NULL,
    error_message VARCHAR(500) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_judge_job_runs_job FOREIGN KEY (judge_job_id) REFERENCES judge_jobs(id),
    CONSTRAINT uk_judge_job_runs_job_try UNIQUE (judge_job_id, try_count),
    CONSTRAINT chk_judge_job_runs_try CHECK (try_count >= 1),
    CONSTRAINT chk_judge_job_runs_result CHECK (result IN ('SUCCEEDED', 'SYSTEM_ERROR', 'LEASE_EXPIRED')),
    CONSTRAINT chk_judge_job_runs_time CHECK (finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
