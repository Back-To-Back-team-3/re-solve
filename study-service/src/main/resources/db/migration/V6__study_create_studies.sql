CREATE TABLE studies
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    name                VARCHAR(200) NOT NULL COMMENT '스터디 이름',
    description         VARCHAR(500) NULL COMMENT 'NULL 허용: 선택 입력',
    goal                VARCHAR(30)  NOT NULL COMMENT '목표 (취업·학습 등 Enum)',
    skill_level         VARCHAR(30)  NOT NULL COMMENT '대상 레벨 (Enum)',
    preferred_language  VARCHAR(30)  NOT NULL COMMENT '선호 언어 (공통 모듈 Enum)',
    active_time_slot    VARCHAR(30)  NOT NULL COMMENT '활동 시간대 (Enum)',
    capacity            INT          NOT NULL COMMENT '정원 2~10 (스터디장 포함)',
    member_count        INT          NOT NULL COMMENT '현재 APPROVED 수. 조건부 UPDATE로만 변경',
    manager_count       INT          NOT NULL COMMENT '현재 MANAGER 수 (상한 2). 조건부 UPDATE로만 변경',
    join_type           VARCHAR(30)  NOT NULL COMMENT 'INSTANT / APPROVAL',
    is_auto_reopen      BOOLEAN      NOT NULL COMMENT '결원 시 ACTIVE -> RECRUITING 자동 복귀 여부',
    status              VARCHAR(30)  NOT NULL COMMENT 'RECRUITING / ACTIVE / CLOSED',
    last_activity_at    DATETIME(6)  NULL COMMENT 'NULL 허용: 활동 전. 콜드 스타트 정렬용',
    closed_at           DATETIME(6)  NULL COMMENT 'NULL 허용: CLOSED 전이 시각',
    version             BIGINT       NOT NULL COMMENT '낙관적 락. 카운터 UPDATE에서도 +1',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_studies_status_created_at (status, created_at),
    INDEX idx_studies_status_skill_level (status, skill_level),
    INDEX idx_studies_status_last_activity_at (status, last_activity_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
