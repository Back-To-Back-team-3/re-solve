CREATE TABLE member_replicas (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    profile_image_url VARCHAR(500) NULL,
    member_status VARCHAR(30) NOT NULL,
    source_version BIGINT NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_member_replicas_member_id UNIQUE (member_id)
);
