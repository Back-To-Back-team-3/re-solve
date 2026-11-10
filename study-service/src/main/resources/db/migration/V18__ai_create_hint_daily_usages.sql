CREATE TABLE hint_daily_usages
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    usage_date DATE        NOT NULL COMMENT 'Asia/Seoul 기준 날짜',
    used_count INT         NOT NULL COMMENT '0~20',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hint_daily_usages_member_usage_date UNIQUE (member_id, usage_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
