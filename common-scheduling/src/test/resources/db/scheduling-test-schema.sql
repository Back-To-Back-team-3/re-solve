-- 서비스의 Flyway 이력과 분리한 실제 JDBC 잠금 검증용 표준 스키마다.
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
