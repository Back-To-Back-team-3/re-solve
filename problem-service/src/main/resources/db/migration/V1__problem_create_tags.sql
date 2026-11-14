CREATE TABLE tags (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '태그 ID',
    name VARCHAR(200) NOT NULL COMMENT '중복을 허용하지 않는 태그 이름',
    display_order INT NOT NULL COMMENT '태그 목록의 기본 표시 순서',
    created_at DATETIME(6) NOT NULL COMMENT '태그 생성 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '태그 최종 수정 시각',
    version BIGINT NOT NULL COMMENT '동시 수정 충돌을 감지하는 낙관적 락 버전',
    PRIMARY KEY (id),
    CONSTRAINT uk_tags_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제 분류에 사용하는 알고리즘 태그와 표시 순서를 관리한다';
