CREATE TABLE hint_requests
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    member_id             BIGINT      NOT NULL,
    problem_id            BIGINT      NOT NULL,
    problem_revision_id   BIGINT      NOT NULL COMMENT 'problem.problem_revisions.id — 요청 시점 공개 회차(캐시 키)',
    level                 INT         NOT NULL COMMENT '1 / 2 / 3',
    status                VARCHAR(30) NOT NULL COMMENT 'REQUESTED / GENERATING / READY / FALLBACK / BLOCKED',
    active_level          INT GENERATED ALWAYS AS (IF(status IN ('REQUESTED', 'GENERATING'), level, NULL)) VIRTUAL,
    is_cached             BOOLEAN     NOT NULL COMMENT '캐시 적중 여부',
    is_fallback           BOOLEAN     NOT NULL COMMENT '폴백 응답 여부',
    fallback_reason       VARCHAR(30) NULL COMMENT 'TIMEOUT / LLM_ERROR / GUARDRAIL / RECOVERED',
    blocked_reason        VARCHAR(30) NULL COMMENT 'EXAM / DIAGNOSIS / DAILY_LIMIT',
    content               TEXT        NULL,
    generation_started_at DATETIME(6) NULL,
    completed_at          DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hint_requests_member_problem_active_level UNIQUE (member_id, problem_id, active_level),
    INDEX idx_hint_requests_member_problem_level (member_id, problem_id, level),
    INDEX idx_hint_requests_status_generation_started_at (status, generation_started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
