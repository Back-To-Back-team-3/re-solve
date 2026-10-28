CREATE TABLE problem_body_assets (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '본문 파일 ID',
    problem_id BIGINT NULL COMMENT '연결된 문제; 최초 연결 전 NULL',
    owner_id BIGINT NOT NULL COMMENT '파일 작성 관리자; members.id 외부 참조, FK 없음',
    status VARCHAR(30) NOT NULL COMMENT '연결 상태: PENDING / LINKED / DISCARDED',
    object_key VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'S3 객체 키 (회차별 불변 경로)',
    object_version VARCHAR(200) NULL COMMENT 'NULL 허용: 버전 관리 미사용 버킷',
    sha256 CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'SHA-256 체크섬',
    size_bytes BIGINT NOT NULL COMMENT '파일 크기(바이트)',
    created_at DATETIME(6) NOT NULL COMMENT '본문 파일 생성 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '본문 파일 최종 수정 시각',
    version BIGINT NOT NULL COMMENT '동시 수정 충돌을 감지하는 낙관적 락 버전',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_body_assets_problem FOREIGN KEY (problem_id) REFERENCES problems (id),
    CONSTRAINT uk_problem_body_assets_object_key UNIQUE (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제 본문 파일의 S3 객체 메타 정보를 관리한다';
