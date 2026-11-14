CREATE TABLE problem_revision_tests (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회차별 테스트 구성 ID',
    problem_revision_id BIGINT NOT NULL COMMENT '소속 문제 수정 회차',
    test_asset_id BIGINT NOT NULL COMMENT '연결된 테스트 파일',
    test_order INT NOT NULL COMMENT '테스트 번호(결과 화면 표시)',
    is_sample BOOLEAN NOT NULL COMMENT 'true 공개 예제 / false 숨김',
    created_at DATETIME(6) NOT NULL COMMENT '구성 생성 시각',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_revision_tests_revision FOREIGN KEY (problem_revision_id) REFERENCES problem_revisions (id),
    CONSTRAINT fk_problem_revision_tests_test_asset FOREIGN KEY (test_asset_id) REFERENCES problem_test_assets (id),
    CONSTRAINT uk_problem_revision_tests_revision_test_asset UNIQUE (problem_revision_id, test_asset_id),
    CONSTRAINT uk_problem_revision_tests_revision_order UNIQUE (problem_revision_id, test_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제 수정 회차별 테스트 구성을 불변으로 관리한다. 회차마다 공개·숨김 테스트 각 1개 이상은 작성 단계에서 검증한다';
