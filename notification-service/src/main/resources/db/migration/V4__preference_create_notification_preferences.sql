CREATE TABLE notification_preferences
(
    id                           BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                    BIGINT      NOT NULL COMMENT '설정 회원 ID (외부 참조)',
    is_exam_contest_enabled      BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '시험·대회 시작 임박·결과 공개·일정 변경·취소',
    is_study_application_enabled BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '스터디 가입 승인·거절',
    is_assignment_enabled        BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '문제집 배정·마감 임박',
    is_level_changed_enabled     BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '태그 레벨 변경·쿨타임 해제',
    is_hint_ready_enabled        BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '힌트 준비 완료',
    is_github_sync_enabled       BOOLEAN     NOT NULL DEFAULT TRUE COMMENT 'GitHub 동기화 실패',
    created_at                   DATETIME(6) NOT NULL COMMENT '생성 시각',
    updated_at                   DATETIME(6) NOT NULL COMMENT '수정 시각',
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_preferences_member_id UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
