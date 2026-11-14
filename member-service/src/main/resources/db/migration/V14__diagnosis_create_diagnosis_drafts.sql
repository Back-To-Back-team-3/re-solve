CREATE TABLE diagnosis_drafts
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    diagnosis_attempt_id  BIGINT      NOT NULL,
    problem_id            BIGINT      NOT NULL COMMENT '문제 ID (외부 참조)',
    language              VARCHAR(30) NOT NULL COMMENT 'java / python3 / cpp',
    source_code           MEDIUMTEXT  NOT NULL COMMENT '작성 중 코드',
    seq                   BIGINT      NOT NULL COMMENT '클라이언트 순번. WHERE seq < :incomingSeq 조건부 UPDATE',
    saved_at              DATETIME(6) NOT NULL COMMENT '서버 저장 시각',
    version               BIGINT      NOT NULL COMMENT '낙관적 락',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnosis_drafts_attempt FOREIGN KEY (diagnosis_attempt_id) REFERENCES diagnosis_attempts (id),
    CONSTRAINT uk_diagnosis_drafts_attempt_problem_language UNIQUE (diagnosis_attempt_id, problem_id, language)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
