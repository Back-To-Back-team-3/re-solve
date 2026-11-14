CREATE TABLE github_sync_jobs
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    connection_id BIGINT       NOT NULL COMMENT 'github_connections.id 참조',
    submission_id BIGINT       NOT NULL COMMENT '제출 ID (외부 참조)',
    problem_id    BIGINT       NOT NULL COMMENT '문제 ID (외부 참조)',
    problem_title VARCHAR(200) NOT NULL COMMENT '문제명 스냅샷 (SubmissionJudged 이벤트, 커밋 경로·알림용)',
    problem_difficulty INT     NOT NULL COMMENT '문제 난이도 스냅샷 1~3 (SubmissionJudged 이벤트, 커밋 경로용)',
    commit_sha    VARCHAR(64)  NULL COMMENT 'NULL 허용: 커밋 성공 시 SHA',
    status        VARCHAR(30)  NOT NULL COMMENT 'PENDING / RUNNING / RETRY_WAITING / RATE_LIMITED / SUCCEEDED / FAILED / CANCELED',
    retry_count   INT          NOT NULL COMMENT '재시도 횟수 (최대 5, 1분 -> 5분 -> 30분)',
    next_retry_at DATETIME(6)  NULL COMMENT 'NULL 허용: 즉시 실행 또는 완료. 다음 재시도 또는 Rate Limit 해제 시각',
    error_message VARCHAR(500) NULL COMMENT 'NULL 허용: 실패 시 에러 메시지',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_github_sync_jobs_connection FOREIGN KEY (connection_id) REFERENCES github_connections (id),
    CONSTRAINT uk_github_sync_jobs_submission UNIQUE (submission_id),
    KEY idx_github_sync_jobs_status_next_retry_at (status, next_retry_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
