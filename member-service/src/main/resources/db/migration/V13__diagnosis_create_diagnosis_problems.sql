CREATE TABLE diagnosis_problems
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    diagnosis_attempt_id  BIGINT      NOT NULL,
    problem_id            BIGINT      NOT NULL COMMENT '문제 ID (외부 참조)',
    problem_revision_id   BIGINT      NOT NULL COMMENT '출제 시점 회차 (problem.problem_revisions.id)',
    difficulty            INT         NOT NULL COMMENT '1 / 2 / 3 (출제 스냅샷)',
    is_accepted           BOOLEAN     NOT NULL COMMENT '제한 시간 안에 AC를 한 번이라도 받았는지 (레벨 판정)',
    final_submission_id   BIGINT      NULL COMMENT 'NULL 허용: 미제출. 문항별 최종 제출 (외부 참조)',
    final_verdict         VARCHAR(30) NULL COMMENT 'NULL 허용: 미제출·채점 전',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnosis_problems_attempt FOREIGN KEY (diagnosis_attempt_id) REFERENCES diagnosis_attempts (id),
    CONSTRAINT uk_diagnosis_problems_attempt_difficulty UNIQUE (diagnosis_attempt_id, difficulty)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
