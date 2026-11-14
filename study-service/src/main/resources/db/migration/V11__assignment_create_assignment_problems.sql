CREATE TABLE assignment_problems
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    assignment_id        BIGINT       NOT NULL COMMENT 'FK -> assignments.id',
    problem_id           BIGINT       NOT NULL COMMENT '문제 ID (외부 참조). 진행 인정 기준',
    assigned_revision_id BIGINT       NOT NULL COMMENT '배정 당시 공개 회차 (problem.problem_revisions.id, 표시용)',
    problem_title        VARCHAR(200) NOT NULL COMMENT '배정 스냅샷',
    problem_difficulty   INT          NOT NULL COMMENT '배정 스냅샷 (1~3)',
    problem_tags_json    JSON         NOT NULL COMMENT '배정 스냅샷. 검색·정렬 미사용',
    display_order        INT          NOT NULL COMMENT '표시 순서',
    status                VARCHAR(30) NOT NULL COMMENT 'ACTIVE / EXCLUDED (문제 PRIVATE 전환 시)',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_problems_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT uk_assignment_problems_assignment_problem UNIQUE (assignment_id, problem_id),
    INDEX idx_assignment_problems_problem_status (problem_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
