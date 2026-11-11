CREATE TABLE judge_recovery_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    judge_job_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    response_status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_judge_recovery_requests_job FOREIGN KEY (judge_job_id) REFERENCES judge_jobs(id),
    CONSTRAINT uk_judge_recovery_requests_actor_job_key UNIQUE (actor_id, judge_job_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
