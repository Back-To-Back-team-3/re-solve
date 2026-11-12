CREATE TABLE diagnosis_attempts
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id           BIGINT       NOT NULL,
    tag_id              BIGINT       NOT NULL COMMENT '태그 ID (외부 참조)',
    status              VARCHAR(30)  NOT NULL COMMENT 'IN_PROGRESS / CLOSED / SCORED',
    active_tag_id       BIGINT GENERATED ALWAYS AS (IF(status = 'IN_PROGRESS', tag_id, NULL)) STORED
        COMMENT 'NULL 허용: 종료된 응시. 태그당 진행 중 진단 1건',
    started_at          DATETIME(6)  NOT NULL COMMENT '시작 시각',
    ends_at             DATETIME(6)  NOT NULL COMMENT '제한 시각 (started_at + 60분)',
    closed_at           DATETIME(6)  NULL COMMENT 'NULL 허용: 진행 중. 실제 종료 시각 (쿨타임 기준)',
    close_reason        VARCHAR(30)  NULL COMMENT 'NULL 허용: 진행 중. SUBMITTED / EXPIRED',
    score               DECIMAL(6, 2) NULL COMMENT 'NULL 허용: 채점 전. 획득 점수 (총 6점)',
    result_level        VARCHAR(30)  NULL COMMENT 'NULL 허용: 채점 전. 진단 결과 레벨 (LV1~LV3)',
    is_cooldown_exempt  BOOLEAN      NOT NULL COMMENT '채점 장애(FAILED) 발생으로 쿨타임 미적용',
    scored_at           DATETIME(6)  NULL COMMENT 'NULL 허용: 채점 전',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnosis_attempts_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT uk_diagnosis_attempts_member_active_tag UNIQUE (member_id, active_tag_id),
    INDEX idx_diagnosis_attempts_status_ends_at (status, ends_at),
    INDEX idx_diagnosis_attempts_member_tag_closed_at (member_id, tag_id, closed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
