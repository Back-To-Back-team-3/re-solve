CREATE TABLE comments
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL COMMENT 'FK -> posts.id',
    author_id  BIGINT      NOT NULL COMMENT '작성자 (members.id)',
    content    TEXT        NOT NULL,
    status     VARCHAR(30) NOT NULL COMMENT 'VISIBLE / DELETED(작성자) / HIDDEN(운영진)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id),
    INDEX idx_comments_post_created_at (post_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
