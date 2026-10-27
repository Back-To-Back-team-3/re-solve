CREATE TABLE github_connections
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    member_id              BIGINT       NOT NULL COMMENT 'Re:Solve 회원 ID (외부 참조)',
    installation_id        BIGINT       NULL COMMENT 'NULL 허용: 연동 해제 후 파기. GitHub App 설치 ID',
    encrypted_token        VARBINARY(1024) NULL COMMENT 'NULL 허용: GitHub App 방식(설치 토큰 매번 발급)·연동 해제. AES-GCM 암호화 토큰',
    github_login           VARCHAR(50)  NOT NULL COMMENT 'GitHub 사용자명',
    repository_owner       VARCHAR(50)  COLLATE utf8mb4_bin NOT NULL COMMENT '대상 저장소 소유자명',
    repository_name        VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '대상 저장소 이름 (대소문자 구분)',
    is_auto_commit_enabled BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '맞힌 문제 자동 커밋 활성화 여부',
    commit_path_rule       VARCHAR(255) NOT NULL COMMENT '커밋 경로 템플릿 ({난이도}/{문제번호}-{문제명}/…)',
    status                 VARCHAR(30)  NOT NULL COMMENT 'CONNECTED / DISCONNECTED',
    disconnected_reason    VARCHAR(30)  NULL COMMENT 'NULL 허용: 연결 중. USER_REQUEST / AUTH_EXPIRED / WITHDRAWN',
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_github_connections_member_id UNIQUE (member_id),
    CONSTRAINT uk_github_connections_installation_id UNIQUE (installation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
