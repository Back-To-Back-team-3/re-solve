CREATE TABLE learning_profiles
(
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                 BIGINT      NOT NULL,
    goal                      VARCHAR(30) NULL COMMENT 'NULL 허용: 미입력. EMPLOYMENT / CONTEST / STUDY',
    preferred_languages_json  JSON        NULL COMMENT 'NULL 허용: 미입력. java / python3 / cpp 배열',
    active_time_slots_json    JSON        NULL COMMENT 'NULL 허용: 미입력. MORNING / AFTERNOON / EVENING / NIGHT 배열',
    profile_version           BIGINT      NOT NULL COMMENT '프로필 변경 순번 (LearningProfileUpdated 순서 값, 태그 레벨 변경에도 증가)',
    created_at                DATETIME(6) NOT NULL,
    updated_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_learning_profiles_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT uk_learning_profiles_member UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
