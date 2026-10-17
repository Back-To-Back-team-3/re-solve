CREATE TABLE notifications
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    member_id       BIGINT       NOT NULL COMMENT '수신 회원 ID (외부 참조)',
    source_event_id VARCHAR(64)  NOT NULL COMMENT '원천 도메인 이벤트 ID (멱등성 기준)',
    type            VARCHAR(30)  NOT NULL COMMENT '알림 유형',
    title           VARCHAR(200) NOT NULL COMMENT '알림 제목',
    content         VARCHAR(500) NOT NULL COMMENT '알림 본문 메시지',
    link_url        VARCHAR(500) NULL COMMENT 'NULL 허용: 이동 경로 없는 알림',
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '읽음 여부',
    read_at         DATETIME(6)  NULL COMMENT 'NULL 허용: 미읽음',
    created_at      DATETIME(6)  NOT NULL COMMENT '생성 시각',
    updated_at      DATETIME(6)  NOT NULL COMMENT '읽음 처리 시각 갱신',
    PRIMARY KEY (id),
    CONSTRAINT uk_notifications_source_member_type UNIQUE (source_event_id, member_id, type),
    INDEX idx_notifications_member_is_read_created_at (member_id, is_read, created_at),
    INDEX idx_notifications_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
