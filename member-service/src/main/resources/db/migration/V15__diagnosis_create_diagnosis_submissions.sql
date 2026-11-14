CREATE TABLE diagnosis_submissions
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'judge 멱등키 (source_submission_id)',
    diagnosis_attempt_id  BIGINT      NOT NULL,
    problem_id            BIGINT      NOT NULL COMMENT '문제 ID (외부 참조)',
    draft_seq             BIGINT      NOT NULL COMMENT '제출한 Draft 순번',
    submission_type       VARCHAR(30) NOT NULL COMMENT 'MANUAL / AUTO (만료 자동 제출)',
    received_at           DATETIME(6) NOT NULL COMMENT '접수 시각. <= ends_at',
    status                VARCHAR(30) NOT NULL COMMENT 'ACCEPTED / REQUESTED / JUDGED / FAILED',
    submission_id         BIGINT      NULL COMMENT 'NULL 허용: 채점 연결 전 (외부 참조)',
    judge_attempt         INT         NULL COMMENT 'NULL 허용: 채점 전. 반영한 채점 회차',
    verdict               VARCHAR(30) NULL COMMENT 'NULL 허용: 채점 전',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnosis_submissions_attempt FOREIGN KEY (diagnosis_attempt_id) REFERENCES diagnosis_attempts (id),
    CONSTRAINT uk_diagnosis_submissions_attempt_problem_seq UNIQUE (diagnosis_attempt_id, problem_id, draft_seq),
    INDEX idx_diagnosis_submissions_submission (submission_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
