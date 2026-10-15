CREATE TABLE members
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    github_id         BIGINT       NOT NULL COMMENT 'GitHub 사용자 고유 ID (로그인 식별자)',
    github_login      VARCHAR(50)  NOT NULL COMMENT 'GitHub 로그인명',
    nickname          VARCHAR(50)  NOT NULL COMMENT '서비스 표시 이름',
    email             VARCHAR(255) NULL COMMENT 'NULL 허용: GitHub에서 이메일 비공개',
    profile_image_url VARCHAR(500) NULL COMMENT 'NULL 허용: 이미지 미설정',
    role              VARCHAR(30)  NOT NULL COMMENT 'USER / ADMIN',
    status            VARCHAR(30)  NOT NULL COMMENT 'ACTIVE / SUSPENDED / WITHDRAWN',
    profile_version   BIGINT       NOT NULL COMMENT '회원 표시 정보 변경 순번 (member_replicas.source_version 원천)',
    suspended_at      DATETIME(6)  NULL COMMENT 'NULL 허용: 정지 상태가 아님',
    withdrawn_at      DATETIME(6)  NULL COMMENT 'NULL 허용: 탈퇴 전',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_members_github_id UNIQUE (github_id),
    INDEX idx_members_status_created_at (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
