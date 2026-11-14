CREATE TABLE execution_runtimes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_hash CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    language VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,
    judge0_language_id INT NOT NULL,
    runtime_name VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_version VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    compiler_options TEXT COLLATE utf8mb4_0900_bin NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_execution_runtimes_snapshot_hash UNIQUE (snapshot_hash),
    CONSTRAINT chk_execution_runtimes_language CHECK (language IN ('java', 'python3', 'cpp')),
    CONSTRAINT chk_execution_runtimes_judge0_id CHECK (judge0_language_id > 0),
    INDEX idx_execution_runtimes_language_judge0_id (language, judge0_language_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
