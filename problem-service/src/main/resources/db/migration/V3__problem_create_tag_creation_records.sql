-- 최초 응답은 24시간 동안 보존하며 태그 삭제에 영향받지 않도록 FK를 두지 않는다.
CREATE TABLE tag_creation_records (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '태그 생성 요청 기록 ID',
    admin_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '요청한 관리자 ID',
    idempotency_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '관리자별 중복 요청 식별 키',
    request_name VARCHAR(200) NOT NULL COMMENT '최초 요청의 태그 이름; 앞뒤 공백을 포함한 원본 값',
    request_display_order INT NOT NULL COMMENT '최초 요청의 표시 순서; 태그 순번 변경과 무관하게 보존한다',
    expires_at DATETIME(6) NOT NULL COMMENT '접수 후 24시간의 만료 시각; 만료된 키로 새 요청 접수 시 갱신한다',
    result_tag_id BIGINT NULL COMMENT '최초 생성 응답의 태그 ID; 태그 삭제 후에도 보존하며 FK를 두지 않는다',
    result_name VARCHAR(200) NULL COMMENT '최초 생성 응답의 태그 이름; 이후 이름 변경과 무관하게 보존한다',
    result_display_order INT NULL COMMENT '최초 생성 응답의 보정된 표시 순서; 이후 순번 변경과 무관하게 보존한다',
    created_at DATETIME(6) NOT NULL COMMENT '요청 기록 생성 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '요청 기록 최종 수정 시각',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '요청 기록 변경 버전',
    PRIMARY KEY (id),
    CONSTRAINT uk_tag_creation_records_admin_key UNIQUE (admin_id, idempotency_key),
    INDEX idx_tag_creation_records_expires_at (expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='관리자별 태그 생성 요청과 최초 응답을 24시간 보존하고 만료 기록을 주기적으로 삭제한다';
