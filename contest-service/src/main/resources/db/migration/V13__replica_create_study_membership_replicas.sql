CREATE TABLE study_membership_replicas (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    study_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_study_membership_replicas_study_member UNIQUE (study_id, member_id)
);
