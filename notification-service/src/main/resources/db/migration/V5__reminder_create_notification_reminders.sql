CREATE TABLE notification_reminders
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL COMMENT '수신 회원 ID (외부 참조)',
    exam_id          BIGINT       NOT NULL COMMENT '시험 ID (외부 참조)',
    participant_id   BIGINT       NOT NULL COMMENT '시험 참가자 ID (유일 기준)',
    fire_at          DATETIME(6)  NOT NULL COMMENT '발화 예정 시각 (starts_at - 5분)',
    starts_at        DATETIME(6)  NOT NULL COMMENT '시험 시작 시각',
    context_revision INT          NOT NULL COMMENT '반영한 시험 설정 버전 (ExamUpdated 역전 방지)',
    source_event_id  VARCHAR(64)  NOT NULL COMMENT '원천 이벤트 ID (ExamParticipantRegistered 또는 마지막 ExamUpdated)',
    status           VARCHAR(30)  NOT NULL COMMENT '예약 상태 (SCHEDULED / SENT / CANCELED)',
    created_at       DATETIME(6)  NOT NULL COMMENT '생성 시각',
    updated_at       DATETIME(6)  NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_reminders_participant_id UNIQUE (participant_id),
    INDEX idx_notification_reminders_status_fire_at (status, fire_at),
    INDEX idx_notification_reminders_exam_id_status (exam_id, status),
    INDEX idx_notification_reminders_status_updated_at (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
