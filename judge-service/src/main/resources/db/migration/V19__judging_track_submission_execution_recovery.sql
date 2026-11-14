ALTER TABLE judge_jobs
    ADD COLUMN unknown_retry_count INT NULL
        COMMENT '동일 실행 시도의 생성 응답 유실 재생성 횟수',
    ADD COLUMN pending_compile_message MEDIUMTEXT NULL
        COMMENT '완료한 CE 결과와 함께 보존한 최초 컴파일 메시지';

UPDATE judge_jobs SET unknown_retry_count = 0 WHERE unknown_retry_count IS NULL;

ALTER TABLE judge_jobs
    MODIFY COLUMN unknown_retry_count INT NOT NULL
        COMMENT '동일 실행 시도의 생성 응답 유실 재생성 횟수',
    ADD CONSTRAINT chk_judge_jobs_unknown_retry CHECK (unknown_retry_count BETWEEN 0 AND 2);
