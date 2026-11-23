CREATE TABLE posts
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    study_id   BIGINT       NOT NULL COMMENT 'FK -> studies.id',
    author_id  BIGINT       NOT NULL COMMENT '작성자 (members.id)',
    title      VARCHAR(200) NOT NULL,
    content    TEXT         NOT NULL,
    is_pinned  BOOLEAN      NOT NULL COMMENT '상단 고정 여부',
    is_notice  BOOLEAN      NOT NULL COMMENT '공지 여부',
    status     VARCHAR(30)  NOT NULL COMMENT 'VISIBLE / DELETED(작성자) / HIDDEN(운영진)',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_posts_study FOREIGN KEY (study_id) REFERENCES studies (id),
    INDEX idx_posts_study_status_pinned_created_at (study_id, status, is_pinned, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
