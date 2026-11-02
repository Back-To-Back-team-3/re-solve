CREATE TABLE problem_revision_tags (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '문제 회차 태그 연결 ID',
    problem_revision_id BIGINT NOT NULL COMMENT '문제 수정 회차 ID',
    tag_id BIGINT NOT NULL COMMENT '연결 태그 ID',
    created_at DATETIME(6) NOT NULL COMMENT '연결 생성 시각',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_revision_tags_revision FOREIGN KEY (problem_revision_id) REFERENCES problem_revisions (id),
    CONSTRAINT fk_problem_revision_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id),
    CONSTRAINT uk_problem_revision_tags_revision_tag UNIQUE (problem_revision_id, tag_id),
    INDEX idx_problem_revision_tags_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제 수정 회차와 알고리즘 태그의 불변 연결을 관리한다';
