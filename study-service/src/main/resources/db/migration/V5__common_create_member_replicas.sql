CREATE TABLE member_replicas
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    member_id         BIGINT       NOT NULL COMMENT '회원 식별자 (외부 참조)',
    nickname          VARCHAR(50)  NOT NULL COMMENT '회원 닉네임 (탈퇴 시 "탈퇴한 사용자")',
    profile_image_url VARCHAR(500) NULL COMMENT 'NULL 허용: 이미지 미설정·탈퇴',
    member_status     VARCHAR(30)  NOT NULL COMMENT 'ACTIVE / SUSPENDED / WITHDRAWN',
    source_version    BIGINT       NOT NULL COMMENT '원천 순서 값 (지연 이벤트 덮어쓰기 방지)',
    synced_at         DATETIME(6)  NOT NULL COMMENT '마지막 동기화 시각',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_replicas_member_id UNIQUE (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
