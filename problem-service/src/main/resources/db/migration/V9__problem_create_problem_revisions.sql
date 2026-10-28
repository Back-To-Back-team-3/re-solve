CREATE TABLE problem_revisions (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '문제 수정 회차 ID (problemRevisionId)',
    problem_id BIGINT NOT NULL COMMENT '소유 문제',
    revision_number INT NOT NULL COMMENT '문제별 회차 번호 (1부터)',
    body_asset_id BIGINT NOT NULL COMMENT '연결된 본문 파일',
    title VARCHAR(200) NOT NULL COMMENT '문제 제목',
    difficulty INT NOT NULL COMMENT '난이도 1 / 2 / 3',
    function_spec JSON NOT NULL COMMENT '함수 명세 (이름·매개변수·반환 타입)',
    time_limit_ms INT NOT NULL COMMENT '기준 시간 제한(ms); 기본 2000, 최소 1000',
    memory_limit_kb INT NOT NULL COMMENT '기준 메모리 제한(KB); 기본 262144',
    wall_time_limit_ms INT NOT NULL COMMENT 'CPU 제한 + 공통 여유값을 적용한 최종 wall time 제한(ms)',
    output_limit_kb INT NOT NULL COMMENT '테스트별 반환 JSON 크기 제한(KiB); 허용 범위 1~256, 기본 64',
    author_id BIGINT NOT NULL COMMENT '회차 작성 관리자; members.id 외부 참조, FK 없음',
    created_at DATETIME(6) NOT NULL COMMENT '회차 생성 시각',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_revisions_problem FOREIGN KEY (problem_id) REFERENCES problems (id),
    CONSTRAINT fk_problem_revisions_body_asset FOREIGN KEY (body_asset_id) REFERENCES problem_body_assets (id),
    CONSTRAINT uk_problem_revisions_problem_revision_number UNIQUE (problem_id, revision_number),
    INDEX idx_problem_revisions_difficulty (difficulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='공개된 회차는 수정하지 않는 불변 문제 수정 회차를 관리한다';
