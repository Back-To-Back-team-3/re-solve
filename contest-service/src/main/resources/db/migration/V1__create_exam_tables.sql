-- 기준 문서: 도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams),
-- §4.4.2 시험 문제 (exam_problems), §4.4.3 시험 참가자 (exam_participants).
-- 서비스 내부 시험 관계만 FK로 연결하고 외부 서비스 자원은 ID로 보존한다.
-- active_member_id는 DB가 계산한다. 취소된 행은 NULL로 남겨 이력 보존과 재신청을 함께 허용한다.
CREATE TABLE exams
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    study_id            BIGINT         NOT NULL COMMENT 'study.studies.id (외부 참조)',
    creator_id          BIGINT         NOT NULL COMMENT 'members.id (외부 참조)',
    title               VARCHAR(200)   NOT NULL,
    mode                VARCHAR(30)    NOT NULL COMMENT 'FIXED / WINDOW',
    starts_at           DATETIME(6)    NOT NULL,
    ends_at             DATETIME(6)    NOT NULL,
    duration_minutes    INT            NULL COMMENT 'FIXED는 NULL, WINDOW는 개인 제한 시간',
    status              VARCHAR(30)    NOT NULL COMMENT 'SCHEDULED / IN_PROGRESS / CLOSED / FINALIZED / CANCELED',
    exam_revision       INT            NOT NULL DEFAULT 1 COMMENT '시험 설정 변경 순번',
    total_score         DECIMAL(6, 2)  NOT NULL DEFAULT 100.00,
    closed_at           DATETIME(6)    NULL,
    result_revision     BIGINT         NOT NULL DEFAULT 0,
    finalized_at        DATETIME(6)    NULL,
    canceled_at         DATETIME(6)    NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_exams_study_starts_at (study_id, starts_at),
    INDEX idx_exams_status_starts_at (status, starts_at),
    INDEX idx_exams_status_ends_at (status, ends_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE exam_problems
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    exam_id             BIGINT         NOT NULL,
    problem_id          BIGINT         NOT NULL COMMENT 'problem.problems.id (외부 참조)',
    problem_revision_id BIGINT         NOT NULL COMMENT 'problem.problem_revisions.id (외부 참조)',
    problem_title       VARCHAR(200)   NOT NULL,
    revision_number     INT            NOT NULL,
    score               DECIMAL(6, 2)  NOT NULL,
    display_order       INT            NOT NULL COMMENT '1부터 시작하는 시험 안의 표시 순서',
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_problems_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_problems_exam_problem UNIQUE (exam_id, problem_id),
    INDEX idx_exam_problems_problem (problem_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE exam_participants
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    exam_id                 BIGINT         NOT NULL,
    member_id               BIGINT         NOT NULL COMMENT 'members.id (외부 참조)',
    status                  VARCHAR(30)    NOT NULL COMMENT 'REGISTERED / STARTED / FINISHED / ABSENT / CANCELED',
    active_member_id        BIGINT         GENERATED ALWAYS AS (IF(status <> 'CANCELED', member_id, NULL)) STORED,
    participant_revision    BIGINT         NOT NULL DEFAULT 1,
    registered_at           DATETIME(6)    NOT NULL,
    entered_at              DATETIME(6)    NULL COMMENT '최초 입장 시간, 미입장은 NULL',
    personal_ends_at        DATETIME(6)    NOT NULL,
    finished_at             DATETIME(6)    NULL,
    total_score             DECIMAL(6, 2)  NOT NULL DEFAULT 0.00,
    last_valid_submitted_at DATETIME(6)    NULL,
    auto_submitted_at       DATETIME(6)    NULL COMMENT '자동 제출 미완료는 NULL',
    is_ranked               BOOLEAN        NOT NULL DEFAULT FALSE,
    final_rank              INT            NULL,
    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              DATETIME(6)    NOT NULL,
    updated_at              DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_participants_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_participants_exam_active_member UNIQUE (exam_id, active_member_id),
    INDEX idx_exam_participants_member_status (member_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
