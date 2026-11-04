CREATE TABLE exam_participant_histories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_participant_id BIGINT NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    actor_member_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_exam_participant_histories_participant FOREIGN KEY (exam_participant_id) REFERENCES exam_participants (id),
    INDEX idx_exam_participant_histories_participant_created_at (exam_participant_id, created_at)
);
