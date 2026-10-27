CREATE TABLE study_membership_histories
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    membership_id    BIGINT       NOT NULL COMMENT 'FK -> study_memberships.id',
    from_status      VARCHAR(30)  NULL COMMENT 'NULL 허용: 최초 생성',
    to_status        VARCHAR(30)  NOT NULL,
    from_role        VARCHAR(30)  NULL COMMENT 'NULL 허용: 최초 생성',
    to_role          VARCHAR(30)  NOT NULL,
    actor_member_id  BIGINT       NULL COMMENT 'NULL 허용: 스케줄러·이벤트 처리',
    reason           VARCHAR(500) NULL COMMENT 'NULL 허용: 사유 없는 전이',
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_study_membership_histories_membership FOREIGN KEY (membership_id) REFERENCES study_memberships (id),
    INDEX idx_study_membership_histories_membership_created_at (membership_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
