CREATE TABLE study_memberships
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    study_id         BIGINT      NOT NULL COMMENT 'FK -> studies.id',
    member_id        BIGINT      NOT NULL COMMENT '회원 ID (외부 참조)',
    role             VARCHAR(30) NOT NULL COMMENT 'LEADER / MANAGER / MEMBER. PENDING은 MEMBER',
    status           VARCHAR(30) NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / CANCELED / EXPIRED / LEFT / REMOVED',
    active_member_id BIGINT GENERATED ALWAYS AS (
        IF(status IN ('PENDING', 'APPROVED'), member_id, NULL)
        ) VIRTUAL COMMENT 'NULL 허용: 종료된 가입',
    leader_key       BIGINT GENERATED ALWAYS AS (
        IF(role = 'LEADER' AND status = 'APPROVED', study_id, NULL)
        ) VIRTUAL COMMENT 'NULL 허용: 스터디장이 아닌 행',
    applied_at       DATETIME(6) NOT NULL COMMENT '신청 시각 (즉시 가입은 가입 시각)',
    expires_at       DATETIME(6) NULL COMMENT 'NULL 허용: 즉시 가입. PENDING 만료 시각(신청 + 7일)',
    joined_at        DATETIME(6) NULL COMMENT 'NULL 허용: APPROVED 전',
    left_at          DATETIME(6) NULL COMMENT 'NULL 허용: LEFT·REMOVED 전',
    removed_reason   VARCHAR(500) NULL COMMENT 'NULL 허용: 강제 탈퇴가 아닌 경우',
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_study_memberships_study FOREIGN KEY (study_id) REFERENCES studies (id),
    CONSTRAINT uk_study_memberships_study_active_member UNIQUE (study_id, active_member_id),
    CONSTRAINT uk_study_memberships_leader_key UNIQUE (leader_key),
    INDEX idx_study_memberships_status_expires_at (status, expires_at),
    INDEX idx_study_memberships_member_status (member_id, status),
    INDEX idx_study_memberships_study_status (study_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
