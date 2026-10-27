CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL COMMENT '스케줄러 작업 고유 이름',
    lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
    locked_at TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
    locked_by VARCHAR(255) NOT NULL COMMENT '잠금을 획득한 인스턴스 식별자',
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='다중 인스턴스의 스케줄러 동시 실행을 제한한다';
