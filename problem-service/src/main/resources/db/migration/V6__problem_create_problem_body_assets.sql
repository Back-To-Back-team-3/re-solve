CREATE TABLE problem_body_assets (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '본문 파일 ID',
    problem_id BIGINT NULL COMMENT '연결된 문제; 최초 연결 전 NULL',
    owner_id BIGINT NOT NULL COMMENT '파일 작성 관리자; members.id 외부 참조, FK 없음',
    status VARCHAR(30) NOT NULL COMMENT '연결 상태: PENDING / LINKED / DISCARDED / DELETING',
    object_key VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'S3 객체 키 (회차별 불변 경로)',
    object_version VARCHAR(200) NULL COMMENT 'NULL 허용: 저장 미확인·버전 관리 미사용 버킷',
    sha256 CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'SHA-256 체크섬',
    size_bytes BIGINT NOT NULL COMMENT '파일 크기(바이트)',
    upload_expires_at DATETIME(6) NOT NULL COMMENT '업로드 보호 만료 시각; 시작·유효한 갱신 시각 + 24시간',
    uploaded_at DATETIME(6) NULL COMMENT 'S3 저장 결과·체크섬 확인 시각; 미확인·실패는 NULL',
    upload_finished_at DATETIME(6) NULL COMMENT '기존 S3 쓰기·재시도 종료 확인 시각; 결과 불명은 NULL',
    failure_code VARCHAR(100) NULL COMMENT '업로드 검증·저장·최종 연결 실패 분류; 실패 없음은 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '업로드 추적 시작 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '마지막 추적·상태 변경 시각',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_body_assets_problem FOREIGN KEY (problem_id) REFERENCES problems (id),
    CONSTRAINT uk_problem_body_assets_object_key UNIQUE (object_key),
    INDEX idx_problem_body_assets_cleanup (status, upload_finished_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='문제 본문 파일의 S3 객체 메타 정보와 업로드 추적을 관리한다';
