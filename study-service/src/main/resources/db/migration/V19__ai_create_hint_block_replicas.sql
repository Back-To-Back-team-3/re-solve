CREATE TABLE hint_block_replicas
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    member_id        BIGINT      NOT NULL COMMENT '참가자 (미입장 ABSENT 포함)',
    context_type     VARCHAR(30) NOT NULL COMMENT 'EXAM / CONTEST',
    context_id       BIGINT      NOT NULL COMMENT '시험·대회 ID (외부 참조)',
    problem_id       BIGINT      NOT NULL COMMENT '포함 문제',
    starts_at        DATETIME(6) NOT NULL COMMENT '차단 시작 (시험 시작 시각)',
    ends_at          DATETIME(6) NOT NULL COMMENT '차단 종료 (시험 종료 시각)',
    context_revision INT         NOT NULL COMMENT '반영한 examRevision·contestRevision (ExamUpdated 역전 방지)',
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hint_block_replicas_member_context_problem UNIQUE (member_id, context_type, context_id, problem_id),
    INDEX idx_hint_block_replicas_member_problem_ends_at (member_id, problem_id, ends_at),
    INDEX idx_hint_block_replicas_context (context_type, context_id),
    INDEX idx_hint_block_replicas_ends_at (ends_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
