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
