CREATE TABLE notification_withdrawn_members
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    member_id       BIGINT      NOT NULL COMMENT '탈퇴 회원 ID (외부 참조)',
    source_event_id VARCHAR(64) NOT NULL COMMENT '원천 MemberWithdrawn 이벤트 ID',
    source_version  INT         NOT NULL COMMENT '원천 회원 이벤트 버전',
    withdrawn_at    DATETIME(6) NOT NULL COMMENT '회원 탈퇴 시각',
    created_at      DATETIME(6) NOT NULL COMMENT '생성 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_withdrawn_members_member_id UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
