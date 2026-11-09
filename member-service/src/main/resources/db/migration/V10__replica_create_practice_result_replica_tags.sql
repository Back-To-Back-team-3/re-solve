CREATE TABLE practice_result_replica_tags
(
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    practice_result_replica_id  BIGINT      NOT NULL,
    tag_id                      BIGINT      NOT NULL COMMENT '태그 ID (이벤트 스냅샷)',
    created_at                  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_practice_result_replica_tags_replica FOREIGN KEY (practice_result_replica_id)
        REFERENCES practice_result_replicas (id),
    CONSTRAINT uk_practice_result_replica_tags_replica_tag UNIQUE (practice_result_replica_id, tag_id),
    INDEX idx_practice_result_replica_tags_tag (tag_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
