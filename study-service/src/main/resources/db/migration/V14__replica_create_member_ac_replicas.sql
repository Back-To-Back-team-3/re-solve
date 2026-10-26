CREATE TABLE member_ac_replicas
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    submission_id BIGINT      NOT NULL COMMENT '제출 ID (외부 참조)',
    member_id     BIGINT      NOT NULL,
    problem_id    BIGINT      NOT NULL,
    submitted_at  DATETIME(6) NOT NULL COMMENT '제출 접수 시각',
    judge_attempt INT         NOT NULL COMMENT '반영한 채점 회차 (순서 값)',
    verdict       VARCHAR(30) NOT NULL COMMENT '마지막 반영 판정',
    is_accepted   BOOLEAN     NOT NULL COMMENT '인정 후보 여부 (verdict = AC)',
    synced_at     DATETIME(6) NOT NULL COMMENT '마지막 반영 시각',
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_ac_replicas_submission UNIQUE (submission_id),
    INDEX idx_member_ac_replicas_member_problem_accepted (member_id, problem_id, is_accepted, submitted_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
