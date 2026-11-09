CREATE TABLE practice_result_replicas
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    submission_id  BIGINT      NOT NULL COMMENT '제출 ID (외부 참조: judge.submissions.id)',
    member_id      BIGINT      NOT NULL,
    problem_id     BIGINT      NOT NULL COMMENT '문제 ID (외부 참조)',
    difficulty     INT         NOT NULL COMMENT '1 / 2 / 3 (이벤트 스냅샷)',
    verdict        VARCHAR(30) NOT NULL COMMENT '최신 회차 판정',
    judge_attempt  INT         NOT NULL COMMENT '반영한 채점 회차 (순서 값)',
    submitted_at   DATETIME(6) NOT NULL COMMENT '제출 접수 시각',
    synced_at      DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_result_replicas_submission UNIQUE (submission_id),
    INDEX idx_practice_result_replicas_member_problem (member_id, problem_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
