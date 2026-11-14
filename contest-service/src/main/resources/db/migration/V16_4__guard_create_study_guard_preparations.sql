CREATE TABLE contest_study_guard_preparations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    study_id BIGINT NOT NULL,
    operation_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    canceled_exam_ids JSON NOT NULL,
    state VARCHAR(30) NOT NULL,
    operation_result VARCHAR(30) NOT NULL,
    prepared_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_contest_study_guard_preparations_study_operation UNIQUE (study_id, operation_id)
);
