DROP TABLE IF EXISTS persistence_probe;
CREATE TABLE persistence_probe (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL
);
