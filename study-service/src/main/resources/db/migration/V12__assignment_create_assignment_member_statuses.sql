CREATE TABLE assignment_member_statuses
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    assignment_id   BIGINT      NOT NULL COMMENT 'FK -> assignments.id',
    member_id       BIGINT      NOT NULL COMMENT '회원 ID',
    membership_id   BIGINT      NOT NULL COMMENT 'FK -> study_memberships.id (탈퇴 여부 판정)',
    status          VARCHAR(30) NOT NULL COMMENT 'NOT_STARTED / IN_PROGRESS / COMPLETED / INCOMPLETE',
    completed_count INT         NOT NULL COMMENT '인정된 ACTIVE 문제 수 (재계산 값)',
    on_time_count   INT         NOT NULL COMMENT 'PRE_SOLVED + ON_TIME 수 (재계산 값)',
    completed_at    DATETIME(6) NULL COMMENT 'NULL 허용: 미완료',
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_member_statuses_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_assignment_member_statuses_membership FOREIGN KEY (membership_id) REFERENCES study_memberships (id),
    CONSTRAINT uk_assignment_member_statuses_assignment_member UNIQUE (assignment_id, member_id),
    INDEX idx_assignment_member_statuses_member (member_id),
    INDEX idx_assignment_member_statuses_assignment_status (assignment_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
