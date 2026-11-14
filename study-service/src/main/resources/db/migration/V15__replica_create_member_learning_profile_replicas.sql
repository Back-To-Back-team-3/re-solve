CREATE TABLE member_learning_profile_replicas
(
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                 BIGINT      NOT NULL,
    goal                      VARCHAR(30) NULL COMMENT 'NULL 허용: 미입력 항목',
    tag_levels_json           JSON        NULL COMMENT 'NULL 허용: 진입한 태그 없음. {태그: 레벨}. 검색 미사용',
    preferred_languages_json  JSON        NULL COMMENT 'NULL 허용: 미입력 (단일/배열 확정 시 조정)',
    active_time_slots_json    JSON        NULL COMMENT 'NULL 허용: 미입력 (값 체계 미결정)',
    profile_version           BIGINT      NOT NULL COMMENT '원천 프로필 순서 값. 작거나 같은 이벤트 무시',
    synced_at                 DATETIME(6) NOT NULL,
    created_at                DATETIME(6) NOT NULL,
    updated_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_learning_profile_replicas_member UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
