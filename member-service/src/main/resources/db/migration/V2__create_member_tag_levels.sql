CREATE TABLE member_tag_levels
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                BIGINT      NOT NULL,
    tag_id                   BIGINT      NOT NULL COMMENT '태그 ID (외부 참조: problem.tags.id)',
    level                    VARCHAR(30) NOT NULL COMMENT 'LV0 / LV1 / LV2 / LV3 / MASTER (재계산 값)',
    diagnosed_level          VARCHAR(30) NULL COMMENT 'NULL 허용: 진단 미응시. 진단 결과 중 최고 레벨',
    last_diagnosis_ended_at  DATETIME(6) NULL COMMENT 'NULL 허용: 진단 미응시. 쿨타임 계산 기준',
    entered_at               DATETIME(6) NOT NULL COMMENT '태그 최초 진입 시각',
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_member_tag_levels_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT uk_member_tag_levels_member_tag UNIQUE (member_id, tag_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- fk_member_level_histories_diagnosis(diagnosis_attempt_id → diagnosis_attempts.id)는 진단 테이블을 만들 때 추가한다.
CREATE TABLE member_level_histories
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    member_tag_level_id   BIGINT      NOT NULL,
    from_level            VARCHAR(30) NULL COMMENT 'NULL 허용: 최초 진입',
    to_level              VARCHAR(30) NOT NULL,
    reason_type           VARCHAR(30) NOT NULL COMMENT 'ENTRY_SKIP / DIAGNOSIS / PROMOTION / REJUDGE',
    diagnosis_attempt_id  BIGINT      NULL COMMENT 'NULL 허용: 진단이 아닌 변경',
    source_submission_id  BIGINT      NULL COMMENT 'NULL 허용: 제출과 무관한 변경. 근거 제출 (외부 참조)',
    created_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_member_level_histories_tag_level FOREIGN KEY (member_tag_level_id) REFERENCES member_tag_levels (id),
    INDEX idx_member_level_histories_tag_level_created_at (member_tag_level_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
