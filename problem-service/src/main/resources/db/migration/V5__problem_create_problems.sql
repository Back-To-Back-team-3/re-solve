CREATE TABLE problems (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '문제 ID',
    author_id BIGINT NOT NULL COMMENT '작성 관리자; members.id 외부 참조, FK 없음',
    scope VARCHAR(30) NOT NULL COMMENT '용도 구분: GENERAL(일반용) / EXAM_ONLY(시험·대회 전용)',
    visibility VARCHAR(30) NOT NULL COMMENT '공개 상태: PRIVATE(비공개) / PUBLIC(공개) / ARCHIVED(보관, 소프트 딜리트)',
    contest_id BIGINT NULL COMMENT '대회 전용 문제의 대회; 일반 문제는 NULL. 외부 참조, FK 없음',
    published_at DATETIME(6) NULL COMMENT '최초 공개 시각; 공개 전에는 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '문제 생성 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '문제 최종 수정 시각',
    version BIGINT NOT NULL COMMENT '동시 수정 충돌을 감지하는 낙관적 락 버전',
    PRIMARY KEY (id),
    INDEX idx_problems_scope_visibility_created_at (scope, visibility, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제의 작성 관리자·용도 구분·공개 상태를 관리한다';
