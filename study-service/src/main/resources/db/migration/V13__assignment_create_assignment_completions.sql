CREATE TABLE assignment_completions
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    assignment_id       BIGINT      NOT NULL COMMENT 'FK -> assignments.id',
    member_id           BIGINT      NOT NULL COMMENT '회원 ID',
    problem_id          BIGINT      NOT NULL COMMENT '문제 ID',
    status              VARCHAR(30) NOT NULL COMMENT 'COMPLETED / REVOKED (재채점으로 인정 취소)',
    completion_type     VARCHAR(30) NULL COMMENT 'NULL 허용: REVOKED. PRE_SOLVED / ON_TIME / LATE',
    basis_submission_id BIGINT      NULL COMMENT 'NULL 허용: REVOKED. 인정 근거 제출 (외부 참조)',
    basis_submitted_at  DATETIME(6) NULL COMMENT 'NULL 허용: REVOKED. 근거 제출의 접수 시각',
    basis_judge_attempt INT         NULL COMMENT 'NULL 허용: REVOKED. 반영한 채점 회차',
    completed_at        DATETIME(6) NULL COMMENT 'NULL 허용: REVOKED. 인정 반영 시각',
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_completions_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT uk_assignment_completions_assignment_member_problem UNIQUE (assignment_id, member_id, problem_id),
    INDEX idx_assignment_completions_member_problem (member_id, problem_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
