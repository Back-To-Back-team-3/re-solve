CREATE TABLE assignments
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    study_id              BIGINT       NOT NULL COMMENT 'FK -> studies.id',
    title                 VARCHAR(200) NOT NULL,
    tag_id                BIGINT       NULL COMMENT 'NULL 허용: 태그 혼합 문제집. 대표 태그(외부 참조)',
    goal                  VARCHAR(500) NULL COMMENT 'NULL 허용: 선택 입력',
    is_pre_solved_allowed BOOLEAN      NOT NULL COMMENT '기존 풀이 인정 여부',
    starts_at             DATETIME(6)  NOT NULL COMMENT '시작 시각',
    deadline_at           DATETIME(6)  NULL COMMENT 'NULL 허용: 마감 없음',
    status                VARCHAR(30)  NOT NULL COMMENT 'SCHEDULED / OPEN / CLOSED',
    reminder_sent_at      DATETIME(6)  NULL COMMENT 'NULL 허용: 마감 알림 전. 알림 1회 보장용',
    closed_at             DATETIME(6)  NULL COMMENT 'NULL 허용: CLOSED 전',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignments_study FOREIGN KEY (study_id) REFERENCES studies (id),
    INDEX idx_assignments_status_starts_at (status, starts_at),
    INDEX idx_assignments_status_deadline_at (status, deadline_at),
    INDEX idx_assignments_study_starts_at (study_id, starts_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
