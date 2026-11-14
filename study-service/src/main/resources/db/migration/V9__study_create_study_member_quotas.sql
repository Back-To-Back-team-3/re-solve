CREATE TABLE study_member_quotas
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    member_id    BIGINT      NOT NULL COMMENT '회원 ID',
    active_count INT         NOT NULL COMMENT 'CLOSED가 아닌 스터디의 APPROVED 수 (상한 5)',
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_study_member_quotas_member UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
